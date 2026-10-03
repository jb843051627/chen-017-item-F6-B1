package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TItemCatalogVerMapper;
import com.fc.v2.model.auto.TItemCatalogVer;
import com.fc.v2.service.ITItemCatalogVerService;

/**
 * 事项目录条目版次单 Service业务层处理（state-machine 形状：单据流转）
 *
 * 一档档走，不跳档：推进只 +1、回退只 -1；到了顶档（已上柜）不许再推，
 * 在草拟档不许再退；已作废的单两向都拒。过口动作随档落库、留痕翻得着。
 * 删除只作软删（del_flag=1），已上柜（办结）的单连软删都不许——旧账要留底。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemCatalogVerServiceImpl implements ITItemCatalogVerService {

    /** 档次：草拟/复核/付发布/已上柜 */
    private static final int STAGE_DRAFT = 0;
    private static final int MAX_STAGE = 3;
    /** 版次落点：未起/在办/已作废 */
    private static final int STATUS_IDLE = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_VOID = 2;

    @javax.annotation.Resource
    private TItemCatalogVerMapper itemCatalogVerMapper;

    @Override
    public TItemCatalogVer selectTItemCatalogVerById(Long id) {
        TItemCatalogVer r = this.itemCatalogVerMapper.selectById(id);
        if (r != null && r.getDelFlag() != null && r.getDelFlag() == 1) {
            return null;
        }
        return r;
    }

    @Override
    public List<TItemCatalogVer> selectTItemCatalogVerList(QueryWrapper<TItemCatalogVer> queryWrapper) {
        QueryWrapper<TItemCatalogVer> qw = queryWrapper == null ? new QueryWrapper<TItemCatalogVer>() : queryWrapper;
        return this.itemCatalogVerMapper.selectList(qw.eq("del_flag", 0));
    }

    /**
     * 推进一档：草拟→复核→付发布→已上柜，只许 +1。
     * 已上柜（办结）或已作废拒推，原样回 null。
     */
    @Override
    public TItemCatalogVer advance(Long id, String remark) {
        TItemCatalogVer r = selectTItemCatalogVerById(id);
        if (r == null || !movable(r)) {
            return null;
        }
        int st = r.getStage() == null ? STAGE_DRAFT : r.getStage();
        if (st >= MAX_STAGE) {
            return null;
        }
        int next = st + 1;
        UpdateWrapper<TItemCatalogVer> uw = new UpdateWrapper<TItemCatalogVer>()
                .eq("id", id)
                .eq("del_flag", 0)
                .set("stage", next)
                .set("status", STATUS_ACTIVE)
                .set("last_action", remark)
                .set("update_time", new Date());
        if (this.itemCatalogVerMapper.update(null, uw) == 0) {
            return null;
        }
        r.setStage(next);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        return r;
    }

    /**
     * 回退一档：只许 -1。草拟档无处可退，已上柜/已作废拒退，回 null。
     */
    @Override
    public TItemCatalogVer rollback(Long id, String remark) {
        TItemCatalogVer r = selectTItemCatalogVerById(id);
        if (r == null || !movable(r)) {
            return null;
        }
        int st = r.getStage() == null ? STAGE_DRAFT : r.getStage();
        if (st <= STAGE_DRAFT) {
            return null;
        }
        int next = st - 1;
        UpdateWrapper<TItemCatalogVer> uw = new UpdateWrapper<TItemCatalogVer>()
                .eq("id", id)
                .eq("del_flag", 0)
                .set("stage", next)
                .set("status", STATUS_ACTIVE)
                .set("last_action", remark)
                .set("update_time", new Date());
        if (this.itemCatalogVerMapper.update(null, uw) == 0) {
            return null;
        }
        r.setStage(next);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        return r;
    }

    /** 记事改写：已上柜（办结）与已作废的单不许再动 */
    @Override
    public boolean updateContent(Long id, String remark) {
        TItemCatalogVer r = selectTItemCatalogVerById(id);
        if (r == null || !movable(r) || isFinished(r)) {
            return false;
        }
        UpdateWrapper<TItemCatalogVer> uw = new UpdateWrapper<TItemCatalogVer>()
                .eq("id", id)
                .eq("del_flag", 0)
                .set("content", remark)
                .set("update_time", new Date());
        return this.itemCatalogVerMapper.update(null, uw) > 0;
    }

    /**
     * 删除：只软删（del_flag=1）留底；已上柜（办结）不许删，已作废的也不重复删。
     */
    @Override
    public boolean remove(Long id) {
        TItemCatalogVer r = selectTItemCatalogVerById(id);
        if (r == null || isFinished(r)) {
            return false;
        }
        UpdateWrapper<TItemCatalogVer> uw = new UpdateWrapper<TItemCatalogVer>()
                .eq("id", id)
                .eq("del_flag", 0)
                .set("del_flag", 1)
                .set("update_time", new Date());
        return this.itemCatalogVerMapper.update(null, uw) > 0;
    }

    /** 还能流转：非作废 */
    private static boolean movable(TItemCatalogVer r) {
        return r.getStatus() == null || r.getStatus() != STATUS_VOID;
    }

    /** 已办结：顶档已上柜 */
    private static boolean isFinished(TItemCatalogVer r) {
        int st = r.getStage() == null ? STAGE_DRAFT : r.getStage();
        return st >= MAX_STAGE;
    }
}
