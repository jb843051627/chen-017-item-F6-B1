package com.fc.v2.service.impl;

import java.util.Date;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TItemChangeBillMapper;
import com.fc.v2.model.auto.TItemChangeBill;
import com.fc.v2.service.ITItemChangeBillService;

/**
 * 事项要素调整逐级核签单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * 跨入口同一把尺：签批、否决、退回都照本口算——
 * 本口签法 sign_mode：0 任一人落名即齐、1 两名点齐（need_count 记着应落名数时照它）；
 * 本口已落名 sign_count 点齐才挪道次，县核→市核→省定一道道走，末道点齐即已核讫。
 * 处置一成就把情形/道次/落名数写回库里，不许人处置了档还停在原处；
 * 已核讫/已打回是终态，任谁再来都拒。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemChangeBillServiceImpl implements ITItemChangeBillService {

    /** 道次：县核 0／市核 1／省定 2 */
    private static final int FIRST_NODE = 0;
    private static final int MAX_NODE = 2;
    /** 同口并签办法：0 任一人、1 两名点齐 */
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    /** 报批情形：在核 0／已核讫 1／已打回 2 */
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;
    /** 两名点齐签法下缺省应落名数 */
    private static final int DEFAULT_AND_NEED = 2;

    @javax.annotation.Resource
    private TItemChangeBillMapper itemChangeBillMapper;

    @Override
    public TItemChangeBill selectTItemChangeBillById(Long id) {
        TItemChangeBill r = this.itemChangeBillMapper.selectById(id);
        if (r != null && r.getDelFlag() != null && r.getDelFlag() == 1) {
            return null;
        }
        return r;
    }

    /**
     * 签批一票：本口已落名 +1；点齐本口应落名数才挪道次，
     * 末道点齐落"已核讫"。终态单拒签，原样回 null。
     */
    @Override
    public TItemChangeBill approve(Long id, String approver, String comment) {
        TItemChangeBill r = selectTItemChangeBillById(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        if (r.getStatus() != null && r.getStatus() != STATUS_RUNNING) {
            // 已核讫/已打回，终态不再收签
            return null;
        }

        int node = r.getNodeNo() == null ? FIRST_NODE : r.getNodeNo();
        int signed = (r.getSignCount() == null ? 0 : r.getSignCount()) + 1;
        int need = needCountOf(r);

        UpdateWrapper<TItemChangeBill> uw = new UpdateWrapper<TItemChangeBill>()
                .eq("id", id)
                .eq("del_flag", 0)
                .eq("status", STATUS_RUNNING)
                .set("update_time", new Date());

        TItemChangeBill back;
        if (signed >= need) {
            // 本口点齐：末道讫，没到末道进下一道、落名数从 0 重点
            if (node >= MAX_NODE) {
                uw.set("sign_count", signed);
                uw.set("status", STATUS_PASS);
                back = applyAndReload(r, id, uw, node, signed, STATUS_PASS);
            } else {
                uw.set("node_no", node + 1);
                uw.set("sign_count", 0);
                uw.set("status", STATUS_RUNNING);
                back = applyAndReload(r, id, uw, node + 1, 0, STATUS_RUNNING);
            }
        } else {
            // 本口还缺点名数：道次不动、情形仍在核
            uw.set("sign_count", signed);
            back = applyAndReload(r, id, uw, node, signed, STATUS_RUNNING);
        }
        return back;
    }

    /**
     * 否决：在核中的单落"已打回"，情形当场写回；终态单拒，回 null。
     */
    @Override
    public TItemChangeBill reject(Long id, String approver, String comment) {
        TItemChangeBill r = selectTItemChangeBillById(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        if (r.getStatus() != null && r.getStatus() != STATUS_RUNNING) {
            return null;
        }
        UpdateWrapper<TItemChangeBill> uw = new UpdateWrapper<TItemChangeBill>()
                .eq("id", id)
                .eq("del_flag", 0)
                .eq("status", STATUS_RUNNING)
                .set("status", STATUS_VETO)
                .set("update_time", new Date());
        if (this.itemChangeBillMapper.update(null, uw) == 0) {
            return null;
        }
        r.setStatus(STATUS_VETO);
        return r;
    }

    /**
     * 退回上一环节：只在核中、且不在头道时可退；退一道并把上一道落名数清 0 重点。
     */
    @Override
    public TItemChangeBill rollback(Long id, String comment) {
        TItemChangeBill r = selectTItemChangeBillById(id);
        if (r == null) {
            return null;
        }
        if (r.getStatus() != null && r.getStatus() != STATUS_RUNNING) {
            return null;
        }
        int node = r.getNodeNo() == null ? FIRST_NODE : r.getNodeNo();
        if (node <= FIRST_NODE) {
            return null;
        }
        UpdateWrapper<TItemChangeBill> uw = new UpdateWrapper<TItemChangeBill>()
                .eq("id", id)
                .eq("del_flag", 0)
                .eq("status", STATUS_RUNNING)
                .set("node_no", node - 1)
                .set("sign_count", 0)
                .set("update_time", new Date());
        if (this.itemChangeBillMapper.update(null, uw) == 0) {
            return null;
        }
        r.setNodeNo(node - 1);
        r.setSignCount(0);
        r.setStatus(STATUS_RUNNING);
        return r;
    }

    /**
     * 本口应落名数：need_count 记着正数照它；没记照签法——
     * 两名点齐签法要 2，任一人签法 1 即齐。
     */
    private static int needCountOf(TItemChangeBill r) {
        if (r.getNeedCount() != null && r.getNeedCount() > 0) {
            return r.getNeedCount();
        }
        return r.getSignMode() != null && r.getSignMode() == MODE_AND ? DEFAULT_AND_NEED : 1;
    }

    /** 条件更新落库；抢不到（并发/终态）回 null，抢到把内存对象也同步成新账 */
    private TItemChangeBill applyAndReload(TItemChangeBill r, Long id, UpdateWrapper<TItemChangeBill> uw,
                                           int node, int signCount, int status) {
        if (this.itemChangeBillMapper.update(null, uw) == 0) {
            return null;
        }
        r.setNodeNo(node);
        r.setSignCount(signCount);
        r.setStatus(status);
        return r;
    }
}
