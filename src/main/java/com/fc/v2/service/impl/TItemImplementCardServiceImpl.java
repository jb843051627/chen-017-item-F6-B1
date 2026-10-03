package com.fc.v2.service.impl;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

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
import com.fc.v2.util.MD5Util;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 实施清单配置卡Service业务层处理
 *
 * 过口与版次同一套规矩：进展只许 待核对(0)→已核对(1)→已冻住(2) 逐态走，
 * 退回只许 已核对→待核对；每推进一态版次序 node_no 加一，并照本版内容生成
 * 校验串落在 content——退回只退态，旧版校验串沉底留着，不许一笔抹掉。
 *
 * 列表只有一把尺：调用方给什么条件就照什么查，服务层只补 del_flag=0，
 * 分页由调用方（Controller）统一掐，明细页与下载/导出才对得上。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TItemImplementCardServiceImpl extends ServiceImpl<TItemImplementCardMapper, TItemImplementCard> implements ITItemImplementCardService {

    /** 进展：待核对 */
    private static final int STATUS_PENDING = 0;
    /** 进展：已核对 */
    private static final int STATUS_CHECKED = 1;
    /** 进展：已冻住 */
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
     * 列表只在调用方条件上补未删口径；不硬塞 status=0、不自起分页，
     * 明细页翻到的与下载/导出的才是同一笔账。
     */
    @Override
    public List<TItemImplementCard> selectTItemImplementCardList(Wrapper<TItemImplementCard> queryWrapper) {
        QueryWrapper<TItemImplementCard> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TItemImplementCard>) queryWrapper
                : new QueryWrapper<TItemImplementCard>();
        wrapper.eq("del_flag", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTItemImplementCard(TItemImplementCard record) {
        if (record == null) {
            return 0;
        }
        TItemDeptArch refArch = siteArch(record.getSiteId());
        if (refArch == null) {
            // 底册查无 / 已撤并：一处紧一处松不行，新增与修改同一道守
            return 0;
        }

        record.setCreateBy(record.getBillNo());
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
            record.setStatus(STATUS_PENDING);
        }
        if (record.getNodeNo() == null) {
            record.setNodeNo(0);
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

        // 修改守的与新增同一把尺：单子挂着的部门撤了并了，不许再改了落库
        TItemImplementCard cur = this.baseMapper.selectById(record.getId());
        if (cur == null || (cur.getDelFlag() != null && cur.getDelFlag() == 1)) {
            return 0;
        }
        Integer siteId = record.getSiteId() == null ? cur.getSiteId() : record.getSiteId();
        TItemDeptArch refArch = siteArch(siteId);
        if (refArch == null) {
            return 0;
        }
        // 已冻住的卡不走编辑口
        if (cur.getStatus() != null && cur.getStatus() == STATUS_FROZEN) {
            return 0;
        }
        if (record.getSiteId() != null) {
            record.setSiteNo(refArch.getSiteNo());
        }

        record.setUpdateTime(new Date());
        return this.baseMapper.update(record, new UpdateWrapper<TItemImplementCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    /**
     * 推进一态：待核对→已核对→已冻住。
     * 存量空状态按待核对接（不再 NPE）；只许逐态，到顶不动；
     * 每成一态版次序加一、校验串照新版内容钉住，两笔同回。
     */
    @Override
    public int advanceTItemImplementCard(Long id) {
        if (id == null) {
            return 0;
        }
        TItemImplementCard cur = this.baseMapper.selectById(id);
        if (cur == null || cur.getDelFlag() == null || cur.getDelFlag().intValue() == 1) {
            return 0;
        }
        int st = cur.getStatus() == null ? STATUS_PENDING : cur.getStatus().intValue();
        int next;
        if (st == STATUS_PENDING) {
            next = STATUS_CHECKED;
        } else if (st == STATUS_CHECKED) {
            next = STATUS_FROZEN;
        } else {
            // 已冻住到顶，不跳态
            return 0;
        }
        int nodeNo = (cur.getNodeNo() == null ? 0 : cur.getNodeNo().intValue()) + 1;
        String checksum = checksum(cur, nodeNo);

        UpdateWrapper<TItemImplementCard> uw = new UpdateWrapper<TItemImplementCard>()
                .eq("id", id).eq("del_flag", 0);
        // 条件更新：只认档里此刻仍是原态的行，并发抢不到即不成。
        // 存量空状态与待核对同论（status IS NULL 也算待核对），不能拿 status=NULL 去比。
        if (cur.getStatus() == null) {
            uw.and(w -> w.isNull("status").or().eq("status", STATUS_PENDING));
        } else {
            uw.eq("status", st);
        }
        uw.set("status", next);
        uw.set("node_no", nodeNo);
        uw.set("content", checksum);
        uw.set("update_time", new Date());
        return this.baseMapper.update(null, uw);
    }

    /**
     * 退回一态：只许 已核对(1)→待核对(0)。待核对无处可退、已冻住不许退。
     * 只退状态，旧版校验串 content 原样沉底留痕。
     */
    @Override
    public int revertTItemImplementCard(Long id) {
        if (id == null) {
            return 0;
        }
        TItemImplementCard cur = this.baseMapper.selectById(id);
        if (cur == null || cur.getDelFlag() == null || cur.getDelFlag().intValue() == 1) {
            return 0;
        }
        Integer st = cur.getStatus();
        if (st == null || st.intValue() != STATUS_CHECKED) {
            return 0;
        }
        UpdateWrapper<TItemImplementCard> uw = new UpdateWrapper<TItemImplementCard>()
                .eq("id", id).eq("del_flag", 0)
                .eq("status", STATUS_CHECKED);
        uw.set("status", STATUS_PENDING);
        // 刻意不清 content：旧版校验串沉底留痕
        uw.set("update_time", new Date());
        return this.baseMapper.update(null, uw);
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

    /** 承办底册：在编才回得出；查无或已撤并(status=1)一律返回 null */
    private TItemDeptArch siteArch(Integer siteId) {
        if (siteId == null) {
            return null;
        }
        TItemDeptArch arch = itemDeptArchMapper.selectOne(new QueryWrapper<TItemDeptArch>()
                .eq("id", siteId).eq("del_flag", 0));
        if (arch == null || (arch.getStatus() != null && arch.getStatus() == 1)) {
            return null;
        }
        return arch;
    }

    /** 校验串随版次序走：卡号+新版次+部门+年办件量+承诺时限，同版同串 */
    private String checksum(TItemImplementCard cur, int nodeNo) {
        StringBuilder sb = new StringBuilder();
        sb.append(cur.getBillNo() == null ? "" : cur.getBillNo()).append('|');
        sb.append(nodeNo).append('|');
        sb.append(cur.getSiteNo() == null ? "" : cur.getSiteNo()).append('|');
        sb.append(cur.getQty() == null ? "" : cur.getQty().toPlainString()).append('|');
        sb.append(cur.getFineAmt() == null ? "" : cur.getFineAmt().toPlainString());
        return MD5Util.encode(sb.toString());
    }
}
