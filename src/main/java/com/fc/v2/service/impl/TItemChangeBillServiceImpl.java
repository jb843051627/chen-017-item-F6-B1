package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TItemChangeBillMapper;
import com.fc.v2.model.auto.TItemChangeBill;
import com.fc.v2.service.ITItemChangeBillService;

import java.util.Date;

/**
 * 事项要素调整逐级核签单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * 同一套并签规矩照 sign_mode 走：0 任一人落名本口即过；1 两名点齐
 *（已落名数 sign_count 到应落名数 need_count）本口才过。票票都先落在本口计数上，
 * 点齐了才挪道次；道次 0..2（县核/市核/省定），过了省定即核讫。
 * 已核讫/已打回都是终态，任何入口都不许再往里落票。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemChangeBillServiceImpl implements ITItemChangeBillService {

    private static final int MAX_NODE = 2;
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    /** AND（两名点齐）未维护应落名数时的兜底 */
    private static final int DEFAULT_AND_NEED = 2;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    @javax.annotation.Resource
    private TItemChangeBillMapper itemChangeBillMapper;

    @Override
    public TItemChangeBill selectTItemChangeBillById(Long id) {
        if (id == null) {
            return null;
        }
        return this.itemChangeBillMapper.selectOne(new QueryWrapper<TItemChangeBill>()
                .eq("id", id)
                .eq("del_flag", 0)
                .last("limit 1"));
    }

    /**
     * 签批一票。票先落在本口计数；本口规矩满足才进下一道次，过省定即核讫。
     * 单子查不到 / 落名人空着 / 已是终态，一律不收，返回 null。
     */
    @Override
    public TItemChangeBill approve(Long id, String approver, String comment) {
        TItemChangeBill r = loadActive(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }

        int signed = (r.getSignCount() == null ? 0 : r.getSignCount()) + 1;
        r.setSignCount(signed);
        appendRemark(r, approver, comment);

        if (!mouthSatisfied(r, signed)) {
            // 本口还没点齐：票照留（计数已写回），道次不挪
            r.setStatus(STATUS_RUNNING);
            touch(r);
            this.itemChangeBillMapper.updateById(r);
            return r;
        }

        // 本口点齐：计数清给下一口，道次进一；省定点齐即核讫
        int node = (r.getNodeNo() == null ? 0 : r.getNodeNo()) + 1;
        r.setNodeNo(node);
        r.setSignCount(0);
        r.setStatus(node > MAX_NODE ? STATUS_PASS : STATUS_RUNNING);
        touch(r);
        this.itemChangeBillMapper.updateById(r);
        return r;
    }

    /**
     * 否决：状态真真切切写回"已打回"，不允许只 update 一遍空壳
     *（处置过状态却没跟着落库，翻账就是死无对证）。
     */
    @Override
    public TItemChangeBill reject(Long id, String approver, String comment) {
        TItemChangeBill r = loadActive(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        r.setStatus(STATUS_VETO);
        appendRemark(r, approver, comment);
        touch(r);
        this.itemChangeBillMapper.updateById(r);
        return r;
    }

    /** 退回上一环节：县核（道次0）无处可退；只退在核中的单子，终态不动 */
    @Override
    public TItemChangeBill rollback(Long id, String comment) {
        TItemChangeBill r = loadActive(id);
        if (r == null) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        if (node <= 0) {
            return null;
        }
        r.setNodeNo(node - 1);
        r.setSignCount(0);
        r.setStatus(STATUS_RUNNING);
        if (comment != null && !comment.trim().isEmpty()) {
            r.setRemark(comment.trim());
        }
        touch(r);
        this.itemChangeBillMapper.updateById(r);
        return r;
    }

    /** 取出还能落票的单子：存在、未删、在核中；终态（核讫/打回）一律不再受票 */
    private TItemChangeBill loadActive(Long id) {
        TItemChangeBill r = selectTItemChangeBillById(id);
        if (r == null) {
            return null;
        }
        Integer st = r.getStatus();
        if (st != null && st != STATUS_RUNNING) {
            return null;
        }
        return r;
    }

    /** 本口规矩满足没：任一人一票即足；两名点齐看已落名到没到应落名数 */
    private boolean mouthSatisfied(TItemChangeBill r, int signed) {
        int mode = r.getSignMode() == null ? MODE_OR : r.getSignMode();
        if (mode == MODE_AND) {
            int need = r.getNeedCount() == null || r.getNeedCount() <= 0
                    ? DEFAULT_AND_NEED : r.getNeedCount();
            return signed >= need;
        }
        return signed >= 1;
    }

    private void appendRemark(TItemChangeBill r, String approver, String comment) {
        String line = approver.trim() + (comment == null || comment.trim().isEmpty()
                ? "" : "：" + comment.trim());
        String prev = r.getRemark();
        r.setRemark(prev == null || prev.isEmpty() ? line : prev + "；" + line);
    }

    private void touch(TItemChangeBill r) {
        r.setUpdateTime(new Date());
    }
}
