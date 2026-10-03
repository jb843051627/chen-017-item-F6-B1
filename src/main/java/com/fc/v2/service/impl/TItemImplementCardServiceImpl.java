package com.fc.v2.service.impl;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TItemImplementCardMapper;
import com.fc.v2.mapper.auto.TItemDeptArchMapper;
import com.fc.v2.model.auto.TItemImplementCard;
import com.fc.v2.model.auto.TItemDeptArch;
import com.fc.v2.service.ITItemImplementCardService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 实施清单配置卡Service业务层处理
 *
 * 明细页与下载同一把尺：列表照调用方递来的条件查、分页由控制器统一 startPage，
 * 服务层不再偷塞 status=0、不再另起 startPage(1,10)，屏上翻着什么就导什么。
 * 进展一档档走：待核对(0)→已核对(1)→已冻住(2)，不跳态、不回退越界；
 * 每推一档生成随版次序走的核验串；退回不抹旧串，沉底留着翻旧账。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TItemImplementCardServiceImpl extends ServiceImpl<TItemImplementCardMapper, TItemImplementCard> implements ITItemImplementCardService {

    /** 进展：待核对／已核对／已冻住 */
    private static final int STATUS_TODO = 0;
    private static final int STATUS_CHECKED = 1;
    private static final int STATUS_FROZEN = 2;

    @Autowired
    private TItemDeptArchMapper itemDeptArchMapper;

    @Override
    public TItemImplementCard selectTItemImplementCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TItemImplementCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    /**
     * 照调用方的条件查，只补 del_flag=0 一道底；不另起分页、不偷塞 status——
     * 控制器 startPage 递来的页次页量才算数，明细页与下载同尺。
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<TItemImplementCard> selectTItemImplementCardList(Wrapper<TItemImplementCard> queryWrapper) {
        QueryWrapper<TItemImplementCard> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TItemImplementCard>) queryWrapper
                : new QueryWrapper<TItemImplementCard>();
        return this.baseMapper.selectList(wrapper.eq("del_flag", 0));
    }

    @Override
    public int insertTItemImplementCard(TItemImplementCard record) {
        if (record == null) {
            return 0;
        }

        record.setCreateBy(record.getBillNo());
        TItemDeptArch refArch = itemDeptArchMapper.selectOne(new QueryWrapper<TItemDeptArch>()
                .eq("id", record.getSiteId()).eq("del_flag", 0));
        if (refArch == null) {
            return 0;
        }
        if (refArch.getStatus() != null && refArch.getStatus() == 1) {
            return 0;
        }
        record.setSiteNo(refArch.getSiteNo());
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TItemImplementCard>()
                    .eq("bill_no", record.getBillNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        record.setDelFlag(0);
        if (record.getStatus() == null) {
            record.setStatus(STATUS_TODO);
        }
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTItemImplementCard(TItemImplementCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        if (record.getId() != null && StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TItemImplementCard>()
                    .eq("bill_no", record.getBillNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TItemImplementCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    /**
     * 推进一态：只许 待核对→已核对→已冻住，一档都不跳；
     * 情形缺着的存量单按待核对兼容（不 NPE）。每推一档版次序 +1、
     * 生成随版次序走的核验串，并与情形同一笔写回。
     */
    @Override
    public int advanceTItemImplementCard(Long id) {
        if (id == null) {
            return 0;
        }
        TItemImplementCard cur = this.baseMapper.selectById(id);
        if (cur == null || cur.getDelFlag() == null || cur.getDelFlag() == 1) {
            return 0;
        }
        Integer st = cur.getStatus() == null ? STATUS_TODO : cur.getStatus();
        // 逐态守卫：只有 0→1、1→2 两条路；已冻住不许再推
        int next;
        if (st == STATUS_TODO) {
            next = STATUS_CHECKED;
        } else if (st == STATUS_CHECKED) {
            next = STATUS_FROZEN;
        } else {
            return 0;
        }
        int node = (cur.getNodeNo() == null ? 0 : cur.getNodeNo()) + 1;
        String verify = verifyCode(cur, node);
        UpdateWrapper<TItemImplementCard> uw = new UpdateWrapper<TItemImplementCard>()
                .eq("id", id)
                .eq("del_flag", 0);
        // 条件更新抢当前态：待核对兼容存量 NULL 档（NULL=0 匹配不上，得另带 IS NULL）
        uw.and(w -> {
            w.eq("status", st);
            if (st == STATUS_TODO) {
                w.or().isNull("status");
            }
        });
        uw.set("status", next);
        uw.set("node_no", node);
        // 核验串随版次序走，推进必生成，落档可查
        uw.set("content", verify);
        uw.set("update_time", new Date());
        return this.baseMapper.update(null, uw);
    }

    /**
     * 退回一态：只许 已核对→待核对；待核对无处可退、已冻住不许直接退。
     * 旧核验串不清——沉底留痕，翻旧账时对得上每一回版次。
     */
    @Override
    public int revertTItemImplementCard(Long id) {
        if (id == null) {
            return 0;
        }
        TItemImplementCard cur = this.baseMapper.selectById(id);
        if (cur == null || cur.getDelFlag() == null || cur.getDelFlag() == 1) {
            return 0;
        }
        Integer st = cur.getStatus();
        if (st == null || st != STATUS_CHECKED) {
            return 0;
        }
        UpdateWrapper<TItemImplementCard> uw = new UpdateWrapper<TItemImplementCard>()
                .eq("id", id).eq("del_flag", 0).eq("status", STATUS_CHECKED);
        uw.set("status", STATUS_TODO);
        // 故意不 set content：旧核验串沉底留痕（坑5 修在这）
        uw.set("update_time", new Date());
        return this.baseMapper.update(null, uw);
    }

    /** 核验串：版次序 + 卡关键字段的指印，任一字段改了下一档的串就对不上 */
    private static String verifyCode(TItemImplementCard cur, int node) {
        String raw = str(cur.getBillNo()) + "|" + node + "|" + str(cur.getSiteNo())
                + "|" + (cur.getQty() == null ? "" : cur.getQty().toPlainString())
                + "|" + (cur.getFineAmt() == null ? "" : cur.getFineAmt().toPlainString());
        return "V" + node + "-" + SecureUtil.md5(raw);
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    @Override
    public int deleteTItemImplementCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTItemImplementCardById(Long id) {
        return this.baseMapper.deleteById(id);
    }
}
