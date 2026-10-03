package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TItemCatalogVerMapper;
import com.fc.v2.model.auto.TItemCatalogVer;
import com.fc.v2.service.ITItemCatalogVerService;

/**
 * 事项目录条目版次单 Service业务层处理（state-machine 形状：单据流转）
 *
 * 过口只许逐档挪：推进一档 +1、回退一档 -1，不跳档、不一脚归 0。
 * 档次 0..3（草拟/复核/付发布/已上柜）：已上柜到顶不再推进，草拟无档可退；
 * 已作废（status=2）的单子两头都不许再动。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemCatalogVerServiceImpl implements ITItemCatalogVerService {

    /** 最高档：已上柜 */
    private static final int MAX_STAGE = 3;
    /** 最低档：草拟 */
    private static final int MIN_STAGE = 0;
    /** 版次落点：在办 */
    private static final int STATUS_ACTIVE = 1;
    /** 版次落点：已作废 */
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TItemCatalogVerMapper itemCatalogVerMapper;

    @Override
    public TItemCatalogVer selectTItemCatalogVerById(Long id) {
        if (id == null) {
            return null;
        }
        return this.itemCatalogVerMapper.selectOne(new QueryWrapper<TItemCatalogVer>()
                .eq("id", id)
                .eq("del_flag", 0)
                .last("limit 1"));
    }

    @Override
    public List<TItemCatalogVer> selectTItemCatalogVerList(QueryWrapper<TItemCatalogVer> queryWrapper) {
        QueryWrapper<TItemCatalogVer> qw = queryWrapper == null
                ? new QueryWrapper<TItemCatalogVer>() : queryWrapper;
        return this.itemCatalogVerMapper.selectList(qw.eq("del_flag", 0));
    }

    /**
     * 推进一档：草拟→复核→付发布→已上柜，一次只走一档。
     * 查不到 / 已作废 / 已到顶（已上柜）→ 不收，返回 null。
     */
    @Override
    public TItemCatalogVer advance(Long id, String remark) {
        TItemCatalogVer r = selectTItemCatalogVerById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        int st = r.getStage() == null ? MIN_STAGE : r.getStage();
        if (st >= MAX_STAGE) {
            return null;
        }
        r.setStage(st + 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        r.setUpdateTime(new Date());
        this.itemCatalogVerMapper.updateById(r);
        return r;
    }

    /**
     * 回退一档：一次只退一档。草拟（最低档）无档可退；已作废的不动。
     */
    @Override
    public TItemCatalogVer rollback(Long id, String remark) {
        TItemCatalogVer r = selectTItemCatalogVerById(id);
        if (r == null || isTerminal(r)) {
            return null;
        }
        int st = r.getStage() == null ? MIN_STAGE : r.getStage();
        if (st <= MIN_STAGE) {
            return null;
        }
        r.setStage(st - 1);
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(remark);
        r.setUpdateTime(new Date());
        this.itemCatalogVerMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TItemCatalogVer r = selectTItemCatalogVerById(id);
        if (r == null || isTerminal(r)) {
            return false;
        }
        r.setContent(remark);
        r.setUpdateTime(new Date());
        return this.itemCatalogVerMapper.updateById(r) > 0;
    }

    /** 留底软删：已作废不真删行，只落删除标记，翻账还翻得着 */
    @Override
    public boolean remove(Long id) {
        TItemCatalogVer r = selectTItemCatalogVerById(id);
        if (r == null) {
            return false;
        }
        r.setDelFlag(1);
        r.setUpdateTime(new Date());
        return this.itemCatalogVerMapper.updateById(r) > 0;
    }

    private boolean isTerminal(TItemCatalogVer r) {
        return r.getStatus() != null && r.getStatus() == STATUS_TERMINAL;
    }

}
