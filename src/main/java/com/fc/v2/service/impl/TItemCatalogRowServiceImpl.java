package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TItemCatalogRowMapper;
import com.fc.v2.model.auto.TItemCatalogRow;
import com.fc.v2.service.ITItemCatalogRowService;

import java.math.BigDecimal;
import java.util.ArrayList;

/**
 * 上级目录下发册核对行 Service业务层处理（batch-process 形状：整批提交）
 *
 * 一把尺：逐行校验只走 isValid 一处；合法的落 STATUS_OK，非法的落 STATUS_FAIL
 * 失败明细、原行号原样带着，好坏在同一轮里各归各位，谁也不挡谁。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemCatalogRowServiceImpl implements ITItemCatalogRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;

    @javax.annotation.Resource
    private TItemCatalogRowMapper itemCatalogRowMapper;

    @Override
    public TItemCatalogRow selectTItemCatalogRowById(Long id) {
        return this.itemCatalogRowMapper.selectById(id);
    }

    /**
     * 整批提交，返回**实际新入库的成功行数**：
     * 入参为空（null/空册）→ 0，空册子与"跑不起来"不是一回事，不许抛异常搅翻调用方；
     * 行数超单批上限 → -1，整册一笔都不入库；
     * 同册次重复提交 → 已落过的（册次+原行号）不另插；
     * 逐行校验：合法落 STATUS_OK，非法落 STATUS_FAIL 明细并保留原行号，互不连坐。
     */
    @Override
    public int submitBatch(String batchNo, List<TItemCatalogRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            return -1;
        }

        int ok = 0;
        for (TItemCatalogRow r : rows) {
            // 缺了对象的坑位跳过，不能一条缺值把整轮搅断
            if (r == null) {
                continue;
            }
            String no = resolveBatchNo(batchNo, r);
            r.setBatchNo(no);
            r.setDelFlag(0);

            if (exists(no, r.getRowNo())) {
                // 同一册次同原行号已落过：不重复入库，成功账也不重复记
                continue;
            }

            if (!isValid(r)) {
                r.setStatus(STATUS_FAIL);
                this.itemCatalogRowMapper.insert(r);
                continue;
            }
            r.setStatus(STATUS_OK);
            this.itemCatalogRowMapper.insert(r);
            ok++;
        }
        return ok;
    }

    @Override
    public List<TItemCatalogRow> listErrors(String batchNo) {
        if (batchNo == null || batchNo.trim().isEmpty()) {
            return new ArrayList<TItemCatalogRow>();
        }
        return this.itemCatalogRowMapper.selectList(new QueryWrapper<TItemCatalogRow>()
                .eq("del_flag", 0)
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .orderByAsc("row_no"));
    }

    /** 行规矩：事项码非空、待核项数有值且为正；缺一桩即记失败明细 */
    private boolean isValid(TItemCatalogRow r) {
        return r.getItemCode() != null && !r.getItemCode().trim().isEmpty()
                && r.getQty() != null
                && r.getQty().compareTo(BigDecimal.ZERO) > 0;
    }

    /** 册次以入参为准，入参空着才用行自带的 */
    private String resolveBatchNo(String batchNo, TItemCatalogRow r) {
        if (batchNo != null && !batchNo.trim().isEmpty()) {
            return batchNo.trim();
        }
        return r.getBatchNo() == null ? null : r.getBatchNo().trim();
    }

    /** 同册次同原行号是否已落（行号为空时不按行号去重，只认册次+本行无号不重复不了） */
    private boolean exists(String batchNo, Integer rowNo) {
        if (batchNo == null || rowNo == null) {
            return false;
        }
        Integer cnt = this.itemCatalogRowMapper.selectCount(new QueryWrapper<TItemCatalogRow>()
                .eq("del_flag", 0)
                .eq("batch_no", batchNo)
                .eq("row_no", rowNo)
                .last("limit 1"));
        return cnt != null && cnt > 0;
    }
}
