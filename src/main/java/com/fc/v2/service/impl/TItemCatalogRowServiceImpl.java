package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TItemCatalogRowMapper;
import com.fc.v2.model.auto.TItemCatalogRow;
import com.fc.v2.service.ITItemCatalogRowService;

/**
 * 上级目录下发册核对行 Service业务层处理（batch-process 形状：整批提交）
 *
 * 单条缺值只把它自己落进失败明细，后面行照走、合法行照入库，一条烂行不搅断整批；
 * 空批次与跑不起来各是各的：空批回 0；超单批上限回 -1 且一笔都不入库。
 * 同一批次重复提交不重复入库——批里重复行只记头一条，档里已入过的也不再进第二遍。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemCatalogRowServiceImpl implements ITItemCatalogRowService {

    /** 单批上限：超过回 -1，整批不入库（与核查派单单轮上限同一把防拖死的尺） */
    private static final int MAX_ROWS = 500;
    /** 行落地情形：已挂接（入库成功） */
    private static final int STATUS_OK = 1;
    /** 行落地情形：校验不过，落失败明细留底 */
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TItemCatalogRowMapper itemCatalogRowMapper;

    @Override
    public TItemCatalogRow selectTItemCatalogRowById(Long id) {
        return this.itemCatalogRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<TItemCatalogRow> rows) {
        // 空情形接得住：没递一行是"空批"，回 0，不碰 rows.get(0)
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        // 超上限整批不动，一笔都不入库
        if (rows.size() > MAX_ROWS) {
            return -1;
        }
        String no = batchNo == null ? null : batchNo.trim();
        if (no.isEmpty()) {
            no = null;
        }

        Set<String> seenInBatch = new HashSet<String>();
        List<TItemCatalogRow> toInsert = new ArrayList<TItemCatalogRow>();
        int success = 0;
        for (TItemCatalogRow r : rows) {
            // 先校验：事项码缺/空白、待核项数缺着或非正，落失败明细，不挡后面行
            if (!isValid(r)) {
                insertFail(no, r);
                continue;
            }
            r.setItemCode(r.getItemCode().trim());
            r.setBatchNo(no);
            String key = bizKey(r);
            // 批里同事项同事由同止点的重复行：只认头一条
            if (!seenInBatch.add(key)) {
                continue;
            }
            // 该批档里已入过的不进第二遍（只避已挂接的，失败过的更正后仍可再入）
            QueryWrapper<TItemCatalogRow> existsQw = new QueryWrapper<TItemCatalogRow>()
                    .eq("batch_no", no)
                    .eq("status", STATUS_OK)
                    .eq("item_code", r.getItemCode())
                    .eq(r.getReasonType() != null, "reason_type", r.getReasonType())
                    .isNull(r.getReasonType() == null, "reason_type");
            // 日期绑定走墙钟字符串，避开 JDBC serverTimezone 偏移（同核查派单那把尺）
            if (r.getDueAt() != null) {
                existsQw.eq("due_at", com.fc.v2.service.support.CheckDutyClock.ts(r.getDueAt()));
            } else {
                existsQw.isNull("due_at");
            }
            Integer existed = this.itemCatalogRowMapper.selectCount(existsQw.last("limit 1"));
            if (existed != null && existed > 0) {
                continue;
            }
            r.setStatus(STATUS_OK);
            r.setDelFlag(r.getDelFlag() == null ? 0 : r.getDelFlag());
            toInsert.add(r);
        }
        // 合法行先在内存点齐，再一笔笔落；单条落库炸了只丢它自己，不回滚整批
        for (TItemCatalogRow r : toInsert) {
            try {
                this.itemCatalogRowMapper.insert(r);
                success++;
            } catch (RuntimeException ex) {
                insertFail(no, r);
            }
        }
        return success;
    }

    @Override
    public List<TItemCatalogRow> listErrors(String batchNo) {
        return this.itemCatalogRowMapper.selectList(new QueryWrapper<TItemCatalogRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .orderByAsc("row_no"));
    }

    /** 合法：事项码不空白、待核项数为正；其余列不作整批的拦路条件 */
    private static boolean isValid(TItemCatalogRow r) {
        return r != null
                && r.getItemCode() != null && !r.getItemCode().trim().isEmpty()
                && r.getQty() != null && r.getQty().compareTo(BigDecimal.ZERO) > 0;
    }

    /** 校验不过：照原行落一笔失败明细，原册行次原样留着翻旧账 */
    private void insertFail(String batchNo, TItemCatalogRow r) {
        if (r == null) {
            return;
        }
        try {
            r.setBatchNo(batchNo);
            r.setStatus(STATUS_FAIL);
            r.setDelFlag(r.getDelFlag() == null ? 0 : r.getDelFlag());
            this.itemCatalogRowMapper.insert(r);
        } catch (RuntimeException ignore) {
            // 失败明细自身落不下也不挡整批
        }
    }

    /** 业务去重键：事项码+承办部门+事由+止点 */
    private static String bizKey(TItemCatalogRow r) {
        String site = r.getSiteNo() == null ? "" : r.getSiteNo().trim();
        String due = r.getDueAt() == null ? "" : String.valueOf(r.getDueAt().getTime());
        return r.getItemCode() + "|" + site + "|" + r.getReasonType() + "|" + due;
    }
}
