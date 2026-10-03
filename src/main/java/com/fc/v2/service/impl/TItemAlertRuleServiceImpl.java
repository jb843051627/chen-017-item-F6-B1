package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TItemAlertRuleMapper;
import com.fc.v2.model.auto.TItemAlertRule;
import com.fc.v2.service.ITItemAlertRuleService;
import com.fc.v2.service.support.CheckDutyClock;

/**
 * 办件逾期风险判定线 Service业务层处理（rule-eval 形状）
 *
 * 一把尺：定位、列表、角标、可用性，各处判"这条线 at 时刻作不作数"
 * 用的都是同一个口径——del_flag=0、status=0（现行）、启用之日 &lt;= at、
 * 交棒之日为空或晚于 at（交棒当日不含，eff_end 为空即一直现行）。
 * 墙钟字符串比较（同核查派单那把尺），时区不挡道。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TItemAlertRuleServiceImpl implements ITItemAlertRuleService {

    /** 线的情形：现行 */
    private static final int STATUS_ACTIVE = 0;

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
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
            return cb.compareTo(ca);
        }
    };

    @javax.annotation.Resource
    private TItemAlertRuleMapper itemAlertRuleMapper;

    @Override
    public TItemAlertRule selectTItemAlertRuleById(Long id) {
        return this.itemAlertRuleMapper.selectById(id);
    }

    /**
     * at 时刻可用规则：五处入口（定位/列表/角标/求值/可用性）同一把尺——
     * del_flag=0、status=0、eff_start &lt;= at、eff_end 为空或晚于 at。
     * 结果按优先级降序、ruleCode 降序排好，evaluateTop 取头一条即可。
     */
    @Override
    public List<TItemAlertRule> listAvailable(Date at) {
        List<TItemAlertRule> all = this.itemAlertRuleMapper.selectList(
                new QueryWrapper<TItemAlertRule>().eq("del_flag", 0));
        List<TItemAlertRule> avail = new ArrayList<TItemAlertRule>();
        for (TItemAlertRule r : all) {
            if (effective(r, at)) {
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
        TItemAlertRule rule = findRule(ruleCode, at);
        if (rule == null) {
            // 该代号在 at 时刻没有现行版本可用，与"输入越界"同为 0，不抛错
            return 0;
        }
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(new BigDecimal("100")) > 0) {
            return 0;
        }
        List<TItemAlertRule> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        return levelOf(avail.get(0), input);
    }

    /** 口径同 listAvailable：同代号同时刻，列表里点得着的才叫可用 */
    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TItemAlertRule r = this.itemAlertRuleMapper.selectById(id);
        return r != null && effective(r, at);
    }

    /** 角标同尺：与列表逐条点算一致，不另起一把尺 */
    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        return listAvailable(at).size();
    }

    /** 同代号现行版本：在 at 时刻生效的版本里，取交棒最晚的那版（确定性兜底） */
    private TItemAlertRule findRule(String ruleCode, Date at) {
        List<TItemAlertRule> hit = this.itemAlertRuleMapper.selectList(new QueryWrapper<TItemAlertRule>()
                .eq("del_flag", 0).eq("rule_code", ruleCode));
        TItemAlertRule newest = null;
        for (TItemAlertRule r : hit) {
            if (!effective(r, at)) {
                continue;
            }
            if (newest == null || compareEffEnd(r.getEffEnd(), newest.getEffEnd()) > 0) {
                newest = r;
            }
        }
        return newest;
    }

    /**
     * 一把尺：这条线 at 时刻作不作数。
     * status 非现行不算；启用之日缺省当作一直可用的旧档兼容，有则须 &lt;= at；
     * 交棒之日缺省当未交棒，有则当日（不含）起不作数。
     */
    private static boolean effective(TItemAlertRule r, Date at) {
        if (r == null || at == null) {
            return false;
        }
        if (r.getStatus() != null && r.getStatus() != STATUS_ACTIVE) {
            return false;
        }
        if (r.getEffStart() != null
                && CheckDutyClock.ts(r.getEffStart()).compareTo(CheckDutyClock.ts(at)) > 0) {
            return false;
        }
        if (r.getEffEnd() != null
                && CheckDutyClock.ts(r.getEffEnd()).compareTo(CheckDutyClock.ts(at)) <= 0) {
            return false;
        }
        return true;
    }

    /** eff_end 为空（一直现行）排在有交棒日的后面；皆空相等 */
    private static int compareEffEnd(Date a, Date b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return CheckDutyClock.ts(a).compareTo(CheckDutyClock.ts(b));
    }

    /**
     * 分档：等于上限取高一档。某一档阈值缺着的跳过该界继续往下比，
     * 一个缺值不许把整回求值搅断；三档全缺时入第四档（超出高界）。
     */
    private int levelOf(TItemAlertRule rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0)
        {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
