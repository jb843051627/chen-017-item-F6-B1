package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TItemAlertRuleMapper;
import com.fc.v2.model.auto.TItemAlertRule;
import com.fc.v2.service.ITItemAlertRuleService;

/**
 * 办件逾期风险判定线 Service业务层处理（rule-eval 形状）
 *
 * 一把尺两头用：单规则求值（evaluate）、多规则取头名（evaluateTop）、旁路的
 * 可用判定（usable）与计数（countAvailable），全部走同一个"此刻可用"谓词
 * isAvailable，绝不在一处只看 del_flag、另一处又把生效期算上。
 *
 * 时段比较统一走**墙钟字符串**（yyyy-MM-dd HH:mm:ss）：
 * JDBC 的 serverTimezone 与本机时区不对称，直接把 java.util.Date 作参数会整体偏移，
 * 使边界判定在"交棒当日/启用当日"这类用例上错档（实测踩到）。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemAlertRuleServiceImpl implements ITItemAlertRuleService {

    /** 线的情形：现行 */
    private static final int STATUS_CURRENT = 0;

    /** 入参（逾期率百分比）口径：[0,100]，越界与缺参同论，一律不可判 */
    private static final BigDecimal INPUT_MIN = BigDecimal.ZERO;
    private static final BigDecimal INPUT_MAX = new BigDecimal("100");

    /** 优先级降序；并列按 ruleCode 降序（确定性），再并列按交棒日晚者先说话 */
    private static final Comparator<TItemAlertRule> PRIORITY_THEN_CODE_DESC = new Comparator<TItemAlertRule>() {
        @Override
        public int compare(TItemAlertRule a, TItemAlertRule b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            int c = cb.compareTo(ca);
            if (c != 0) {
                return c;
            }
            // 交棒日未定（null）视为最远，排在前；都定了则晚者先
            if (a.getEffEnd() == null && b.getEffEnd() == null) {
                return 0;
            }
            if (a.getEffEnd() == null) {
                return -1;
            }
            if (b.getEffEnd() == null) {
                return 1;
            }
            return b.getEffEnd().compareTo(a.getEffEnd());
        }
    };

    @javax.annotation.Resource
    private TItemAlertRuleMapper itemAlertRuleMapper;

    /** 墙钟字符串：同 alert-rule 实测教训，时段比较不走 Date 参数绑定 */
    private static String ts(Date d) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    @Override
    public TItemAlertRule selectTItemAlertRuleById(Long id) {
        return this.itemAlertRuleMapper.selectById(id);
    }

    /**
     * at 时刻可用规则（口径唯一）：
     * 未删 + 现行（status=0，已让位 status=1 不说话）+ 生效段含此刻
     * （eff_start 空或 &lt;= at；eff_end 空或 at &lt; eff_end，交棒当日不含）。
     * 出参照 PRIORITY_THEN_CODE_DESC 排好，取头名与翻列表共用一份。
     */
    @Override
    public List<TItemAlertRule> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TItemAlertRule>();
        }
        List<TItemAlertRule> all = this.itemAlertRuleMapper.selectList(
                new QueryWrapper<TItemAlertRule>().eq("del_flag", 0));
        List<TItemAlertRule> avail = new java.util.ArrayList<TItemAlertRule>();
        for (TItemAlertRule r : all) {
            if (isAvailable(r, at)) {
                avail.add(r);
            }
        }
        Collections.sort(avail, PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        // 入参口径与 evaluateTop 同一把尺
        if (input.compareTo(INPUT_MIN) < 0 || input.compareTo(INPUT_MAX) > 0) {
            return 0;
        }
        TItemAlertRule rule = findRule(ruleCode, at);
        return rule == null ? 0 : levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(INPUT_MIN) < 0 || input.compareTo(INPUT_MAX) > 0) {
            return 0;
        }
        List<TItemAlertRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    /** 旁路复用（批量导入/报表）：口径与定位/列表一致，只问 isAvailable */
    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        return isAvailable(this.itemAlertRuleMapper.selectById(id), at);
    }

    /** 角标计数：数的就是 listAvailable 那一份，账才凑得拢 */
    @Override
    public int countAvailable(Date at) {
        return listAvailable(at).size();
    }

    /**
     * 同代号里 at 时刻生效的那一版：先过 isAvailable 同一谓词，
     * 再照"让线顺位高者、交棒晚者"取头名（与列表排序同源）。
     */
    private TItemAlertRule findRule(String ruleCode, Date at) {
        List<TItemAlertRule> hit = this.itemAlertRuleMapper.selectList(new QueryWrapper<TItemAlertRule>()
                .eq("del_flag", 0)
                .eq("rule_code", ruleCode));
        TItemAlertRule best = null;
        for (TItemAlertRule r : hit) {
            if (!isAvailable(r, at)) {
                continue;
            }
            if (best == null || PRIORITY_THEN_CODE_DESC.compare(r, best) < 0) {
                best = r;
            }
        }
        return best;
    }

    /**
     * 唯一可用谓词：未删 + 现行 + 生效段含此刻（交棒日不含）。
     * 墙钟字符串比较，避开时区错档。
     */
    private boolean isAvailable(TItemAlertRule r, Date at) {
        if (r == null || at == null) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag() != 0) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus() != STATUS_CURRENT) {
            return false;
        }
        String now = ts(at);
        if (r.getEffStart() != null && ts(r.getEffStart()).compareTo(now) > 0) {
            return false;
        }
        if (r.getEffEnd() != null && now.compareTo(ts(r.getEffEnd())) >= 0) {
            return false;
        }
        return true;
    }

    /**
     * 分档：等于上限取高一档（踩在上限那一条不许落在低一档）。
     * 某档上限未维护则跳过该档，由更高一档接住；三线皆缺给 4。
     */
    private int levelOf(TItemAlertRule rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) < 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) < 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
