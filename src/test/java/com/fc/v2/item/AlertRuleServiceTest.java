package com.fc.v2.item;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fc.v2.mapper.auto.TItemAlertRuleMapper;
import com.fc.v2.model.auto.TItemAlertRule;
import com.fc.v2.service.impl.TItemAlertRuleServiceImpl;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 办件逾期风险判定线：生效期、两入口同口径、优先级排序、端点取高档。
 * H2(MySQL 方言) + 真实 Mapper，不起 Spring 容器。
 */
class AlertRuleServiceTest {

    private SqlSession session;
    private TItemAlertRuleMapper mapper;
    private TItemAlertRuleServiceImpl service;

    private static Date t(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:alert_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t_item_alert_rule ("
                    + "id bigint PRIMARY KEY, rule_code varchar(64), rule_name varchar(128), "
                    + "th1_max decimal(12,2), th2_max decimal(12,2), th3_max decimal(12,2), "
                    + "eff_start datetime, eff_end datetime, priority int, status int, del_flag int, "
                    + "create_by varchar(64), create_time datetime, update_by varchar(64), "
                    + "update_time datetime, remark varchar(500))");
        }
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        GlobalConfig gc = new GlobalConfig();
        gc.setDbConfig(new GlobalConfig.DbConfig());
        cfg.addMapper(TItemAlertRuleMapper.class);
        MybatisSqlSessionFactoryBean fb = new MybatisSqlSessionFactoryBean();
        fb.setDataSource(ds);
        fb.setConfiguration(cfg);
        fb.setGlobalConfig(gc);
        SqlSessionFactory factory = fb.getObject();
        this.session = factory.openSession(true);
        this.mapper = session.getMapper(TItemAlertRuleMapper.class);
        this.service = new TItemAlertRuleServiceImpl();
        Field f = TItemAlertRuleServiceImpl.class.getDeclaredField("itemAlertRuleMapper");
        f.setAccessible(true);
        f.set(service, mapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private TItemAlertRule rule(String code, int priority, int status, String start, String end,
                                int th1, int th2, int th3) {
        TItemAlertRule r = new TItemAlertRule();
        r.setRuleCode(code);
        r.setPriority(priority);
        r.setStatus(status);
        r.setEffStart(start == null ? null : t(start));
        r.setEffEnd(end == null ? null : t(end));
        r.setTh1Max(new BigDecimal(th1));
        r.setTh2Max(new BigDecimal(th2));
        r.setTh3Max(new BigDecimal(th3));
        r.setDelFlag(0);
        mapper.insert(r);
        return r;
    }

    @Test
    @DisplayName("运行时段设限：启用前不可判、段内可判、交棒当刻不含、让位线不说话")
    void effectiveWindowEnforced() {
        rule("R1", 5, 0, "2026-01-01 00:00:00", "2026-07-01 00:00:00", 10, 50, 100);

        // 启用前：被提早判不行
        assertEquals(0, service.evaluate("R1", new BigDecimal("5"), t("2025-12-31 23:59:59")));
        // 启用当刻起算
        assertEquals(1, service.evaluate("R1", new BigDecimal("5"), t("2026-01-01 00:00:00")));
        // 段内
        assertEquals(1, service.evaluate("R1", new BigDecimal("5"), t("2026-03-01 10:00:00")));
        // 交棒当刻不含
        assertEquals(0, service.evaluate("R1", new BigDecimal("5"), t("2026-07-01 00:00:00")));

        // 已让位(status=1)的线不说话
        rule("R2", 5, 1, null, null, 10, 50, 100);
        assertEquals(0, service.evaluate("R2", new BigDecimal("5"), t("2026-03-01 10:00:00")));
        assertFalse(service.usable(ruleId("R2"), t("2026-03-01 10:00:00")));
        assertTrue(service.usable(ruleId("R1"), t("2026-03-01 10:00:00")));
    }

    @Test
    @DisplayName("端点掐进去：正好踩上限取高一档（10→2档, 50→3档, 100→4档）")
    void boundaryTakesHigherTier() {
        rule("R1", 5, 0, null, null, 10, 50, 100);
        assertEquals(1, service.evaluate("R1", new BigDecimal("9.99"), t("2026-03-01 00:00:00")));
        assertEquals(2, service.evaluate("R1", new BigDecimal("10"), t("2026-03-01 00:00:00")));
        assertEquals(3, service.evaluate("R1", new BigDecimal("50"), t("2026-03-01 00:00:00")));
        assertEquals(4, service.evaluate("R1", new BigDecimal("100"), t("2026-03-01 00:00:00")));
        // 越界不可判
        assertEquals(0, service.evaluate("R1", new BigDecimal("100.01"), t("2026-03-01 00:00:00")));
        assertEquals(0, service.evaluate("R1", new BigDecimal("-1"), t("2026-03-01 00:00:00")));
    }

    @Test
    @DisplayName("两入口同一口径：evaluate 与 evaluateTop 都只认生效段内现行线")
    void bothEntriesSameRuler() {
        rule("OLD", 9, 0, "2025-01-01 00:00:00", "2026-07-01 00:00:00", 10, 50, 100);
        rule("OLD", 9, 0, "2026-07-01 00:00:00", null, 20, 60, 100);

        // 上半年照旧版阈值，下半年照新版（同代号交棒）
        assertEquals(2, service.evaluate("OLD", new BigDecimal("15"), t("2026-03-01 00:00:00")));
        assertEquals(1, service.evaluate("OLD", new BigDecimal("15"), t("2026-08-01 00:00:00")));
        // evaluateTop 此刻也只能看到下半年那一条
        assertEquals(1, service.evaluateTop(new BigDecimal("15"), t("2026-08-01 00:00:00")));
    }

    @Test
    @DisplayName("优先级排序生效：高顺位线先说话；计数与列表同源")
    void priorityOrderingAndCount() {
        rule("LOW", 5, 0, null, null, 10, 50, 100);
        rule("HIGH", 9, 0, null, null, 20, 60, 100);
        rule("DEAD", 9, 1, null, null, 20, 60, 100);

        Date at = t("2026-03-01 00:00:00");
        // input=15：LOW 判 2 档、HIGH 判 1 档；取头名 HIGH
        assertEquals(1, service.evaluateTop(new BigDecimal("15"), at));
        // 统计口径 = 列表条数（已让位的不计）
        assertEquals(2, service.countAvailable(at));
        assertEquals(2, service.listAvailable(at).size());
        // 列表头名就是高顺位
        assertEquals("HIGH", service.listAvailable(at).get(0).getRuleCode());
    }

    private Long ruleId(String code) {
        return mapper.selectList(null).stream()
                .filter(x -> code.equals(x.getRuleCode()))
                .map(TItemAlertRule::getId).findFirst().orElse(null);
    }
}
