package com.fc.v2.item;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fc.v2.mapper.auto.TItemAlertRuleMapper;
import com.fc.v2.mapper.auto.TItemCatalogRowMapper;
import com.fc.v2.mapper.auto.TItemCatalogVerMapper;
import com.fc.v2.mapper.auto.TItemChangeBillMapper;
import com.fc.v2.mapper.auto.TItemDeptArchMapper;
import com.fc.v2.mapper.auto.TItemImplementCardMapper;
import com.fc.v2.model.auto.TItemAlertRule;
import com.fc.v2.model.auto.TItemCatalogRow;
import com.fc.v2.model.auto.TItemCatalogVer;
import com.fc.v2.model.auto.TItemChangeBill;
import com.fc.v2.model.auto.TItemDeptArch;
import com.fc.v2.model.auto.TItemImplementCard;
import com.fc.v2.service.impl.TItemAlertRuleServiceImpl;
import com.fc.v2.service.impl.TItemCatalogRowServiceImpl;
import com.fc.v2.service.impl.TItemCatalogVerServiceImpl;
import com.fc.v2.service.impl.TItemChangeBillServiceImpl;
import com.fc.v2.service.impl.TItemImplementCardServiceImpl;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事项域五个兄弟服务的对账测试：H2(MySQL 方言) + 真实 MyBatis-Plus Mapper，不起容器。
 * 规矩同 CheckDispatchRoundTest 一把尺——处置、统计、留痕各处口径一致，空情形接得住。
 */
class ItemSiblingServicesTest {

    private SqlSession session;
    private TItemAlertRuleMapper ruleMapper;
    private TItemCatalogRowMapper rowMapper;
    private TItemCatalogVerMapper verMapper;
    private TItemChangeBillMapper billMapper;
    private TItemImplementCardMapper cardMapper;
    private TItemDeptArchMapper archMapper;

    private TItemAlertRuleServiceImpl ruleService;
    private TItemCatalogRowServiceImpl rowService;
    private TItemCatalogVerServiceImpl verService;
    private TItemChangeBillServiceImpl billService;
    private TItemImplementCardServiceImpl cardService;

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
        ds.setURL("jdbc:h2:mem:item_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        schema(ds);

        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig());
        cfg.addMapper(TItemAlertRuleMapper.class);
        cfg.addMapper(TItemCatalogRowMapper.class);
        cfg.addMapper(TItemCatalogVerMapper.class);
        cfg.addMapper(TItemChangeBillMapper.class);
        cfg.addMapper(TItemImplementCardMapper.class);
        cfg.addMapper(TItemDeptArchMapper.class);

        MybatisSqlSessionFactoryBean fb = new MybatisSqlSessionFactoryBean();
        fb.setDataSource(ds);
        fb.setConfiguration(cfg);
        fb.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = fb.getObject();
        this.session = factory.openSession(true);
        this.ruleMapper = session.getMapper(TItemAlertRuleMapper.class);
        this.rowMapper = session.getMapper(TItemCatalogRowMapper.class);
        this.verMapper = session.getMapper(TItemCatalogVerMapper.class);
        this.billMapper = session.getMapper(TItemChangeBillMapper.class);
        this.cardMapper = session.getMapper(TItemImplementCardMapper.class);
        this.archMapper = session.getMapper(TItemDeptArchMapper.class);

        this.ruleService = new TItemAlertRuleServiceImpl();
        set(ruleService, "itemAlertRuleMapper", ruleMapper);
        this.rowService = new TItemCatalogRowServiceImpl();
        set(rowService, "itemCatalogRowMapper", rowMapper);
        this.verService = new TItemCatalogVerServiceImpl();
        set(verService, "itemCatalogVerMapper", verMapper);
        this.billService = new TItemChangeBillServiceImpl();
        set(billService, "itemChangeBillMapper", billMapper);
        this.cardService = new TItemImplementCardServiceImpl();
        set(cardService, "baseMapper", cardMapper);
        set(cardService, "itemDeptArchMapper", archMapper);

        seedArch(0L, "ZW00", 0);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        NoSuchFieldException last = null;
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                last = e;
                clazz = clazz.getSuperclass();
            }
        }
        throw last;
    }

    // ---------------- 风险判定线：五处口径同源 ----------------

    @Test
    @DisplayName("生效窗：启用前不可用、启用当日可用、交棒当日(不含)起不可用")
    void ruleEffectiveWindowSharedEverywhere() {
        addRule("R1", null, 10, 50, 90, t("2026-03-01 00:00:00"), t("2026-04-01 00:00:00"), 0, 5);

        assertFalse(ruleService.usable(idOfRule("R1"), t("2026-02-28 23:59:59")));
        assertTrue(ruleService.usable(idOfRule("R1"), t("2026-03-01 00:00:00")));
        assertTrue(ruleService.usable(idOfRule("R1"), t("2026-03-31 23:59:59")));
        // 交棒之日不含：4-1 起不作数
        assertFalse(ruleService.usable(idOfRule("R1"), t("2026-04-01 00:00:00")));

        assertEquals(1, ruleService.listAvailable(t("2026-03-15 00:00:00")).size());
        assertEquals(1, ruleService.countAvailable(t("2026-03-15 00:00:00")));
        assertEquals(0, ruleService.listAvailable(t("2026-04-01 00:00:00")).size());
        assertEquals(0, ruleService.evaluate("R1", new BigDecimal("20"), t("2026-04-01 00:00:00")));
        // 窗内照常判档
        assertEquals(1, ruleService.evaluate("R1", new BigDecimal("10"), t("2026-03-15 00:00:00")));
        assertEquals(3, ruleService.evaluate("R1", new BigDecimal("90"), t("2026-03-15 00:00:00")));
    }

    @Test
    @DisplayName("已让位(status=1)的线五处都不认；无交棒日一直现行")
    void ruleRetiredStatusExcluded() {
        addRule("OLD", null, 10, 50, 90, t("2026-01-01 00:00:00"), null, 1, 9);
        addRule("NOW", null, 5, 20, 80, t("2026-01-01 00:00:00"), null, 0, 1);
        Date at = t("2026-03-15 00:00:00");

        assertEquals(1, ruleService.countAvailable(at));
        assertEquals("NOW", ruleService.listAvailable(at).get(0).getRuleCode());
        // 高优先级的让位线不算，evaluateTop 取现行线
        assertEquals(2, ruleService.evaluateTop(new BigDecimal("15"), at));
        assertFalse(ruleService.usable(idOfRule("OLD"), at));
        assertEquals(0, ruleService.evaluate("OLD", new BigDecimal("1"), at));
    }

    @Test
    @DisplayName("某档阈值缺着不搅断：缺中界时跨档继续比")
    void ruleNullThresholdDoesNotBlowUp() {
        addRule("GAP", null, 10, null, 90, t("2026-01-01 00:00:00"), null, 0, 1);
        Date at = t("2026-03-15 00:00:00");
        // 50 落在缺的中界与高界之间 → 第三档，而不是抛错
        assertEquals(3, ruleService.evaluate("GAP", new BigDecimal("50"), at));
        assertEquals(4, ruleService.evaluate("GAP", new BigDecimal("91"), at));
    }

    // ---------------- 整批提交 ----------------

    @Test
    @DisplayName("空批回0；一条缺值落失败明细，合法行照入库不搅断")
    void emptyBatchAndBadRowDoNotBreakRound() {
        assertEquals(0, rowService.submitBatch("B0", new ArrayList<TItemCatalogRow>()));
        assertEquals(0, rowService.submitBatch("B0", null));

        List<TItemCatalogRow> rows = new ArrayList<>();
        rows.add(row("ITEM-A", new BigDecimal("3"), 1));
        rows.add(row(null, new BigDecimal("2"), 1));          // 事项码缺
        rows.add(row("ITEM-C", new BigDecimal("0"), 1));      // 项数非正
        rows.add(row("ITEM-D", new BigDecimal("7"), 2));
        int ok = rowService.submitBatch("B1", rows);
        assertEquals(2, ok);

        List<TItemCatalogRow> errs = rowService.listErrors("B1");
        assertEquals(2, errs.size());
        // 入库的两条在
        Long okCount = rowMapper.selectList(null).stream()
                .filter(x -> Integer.valueOf(1).equals(x.getStatus())).count();
        assertEquals(2L, okCount);
    }

    @Test
    @DisplayName("超单批上限回-1且整批不入库；同批重复提交不重复入库")
    void overLimitAbortsAndNoDuplicateSubmit() {
        List<TItemCatalogRow> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(row("ITEM-" + i, new BigDecimal("1"), 1));
        }
        assertEquals(-1, rowService.submitBatch("BIG", many));
        assertEquals(0, rowMapper.selectList(null).size());

        List<TItemCatalogRow> once = new ArrayList<>();
        once.add(row("ITEM-A", new BigDecimal("1"), 1));
        once.add(row("ITEM-A", new BigDecimal("1"), 1)); // 批里重复
        assertEquals(1, rowService.submitBatch("B2", once));
        // 第二回整批重交：档里已入，不进第二遍
        List<TItemCatalogRow> again = new ArrayList<>();
        again.add(row("ITEM-A", new BigDecimal("1"), 1));
        assertEquals(0, rowService.submitBatch("B2", again));
        assertEquals(1, rowMapper.selectList(null).size());
    }

    // ---------------- 版次单状态机 ----------------

    @Test
    @DisplayName("推进一档档走不跳档；顶档拒推；回退一档、草拟拒退")
    void catalogVerMovesOneStageAtATime() {
        Long id = addVer(0, 1);
        assertEquals(1, verService.advance(id, "a").getStage().intValue());
        assertEquals(2, verService.advance(id, "b").getStage().intValue());
        assertEquals(3, verService.advance(id, "c").getStage().intValue());
        assertNull(verService.advance(id, "d"));
        // 逐档回退
        assertEquals(2, verService.rollback(id, "r").getStage().intValue());
        TItemCatalogVer down0 = verService.rollback(verService.rollback(id, "r").getId(), "r");
        assertEquals(0, down0.getStage().intValue());
        assertNull(verService.rollback(id, "r2"));
    }

    @Test
    @DisplayName("已办结不得改记事不得删；删除是软删留底")
    void catalogVerFinishedLockedAndSoftDeleted() {
        Long id = addVer(0, 1);
        verService.advance(id, "x");
        verService.advance(id, "x");
        TItemCatalogVer fin = verService.advance(id, "x");
        assertEquals(3, fin.getStage().intValue());
        assertFalse(verService.updateContent(id, "改"));
        assertFalse(verService.remove(id));

        Long id2 = addVer(0, 1);
        assertTrue(verService.remove(id2));
        assertNull(verService.selectTItemCatalogVerById(id2));
        // 底册仍在，只是软删
        assertEquals(Integer.valueOf(1), verMapper.selectById(id2).getDelFlag());

        Long id3 = addVer(2, 2); // 已作废
        assertNull(verService.advance(id3, "x"));
        assertFalse(verService.updateContent(id3, "改"));
    }

    // ---------------- 逐级核签 ----------------

    @Test
    @DisplayName("任一人签法：一口一票即过，末道签齐已核讫；旧bug末道状态正确")
    void approveOrModeAcrossNodes() {
        Long id = addBill(0, 0, null, 0, 0);
        TItemChangeBill a1 = billService.approve(id, "zhang", "c");
        assertEquals(1, a1.getNodeNo().intValue());
        assertEquals(0, a1.getSignCount().intValue());
        assertEquals(0, a1.getStatus().intValue());
        billService.approve(id, "wang", "c"); // 到省定
        TItemChangeBill a3 = billService.approve(id, "li", "c");
        assertEquals(2, a3.getNodeNo().intValue());
        assertEquals(1, a3.getStatus().intValue(), "末道签齐应已核讫(status=1)，旧bug误留在核");
        // 终态拒签
        assertNull(billService.approve(id, "late", "c"));
    }

    @Test
    @DisplayName("两名点齐签法：本口两名才挪道次；否决真写回已打回；头道拒退")
    void approveAndModeRejectRollback() {
        Long id = addBill(0, 1, 2, 0, 0);
        TItemChangeBill first = billService.approve(id, "zhang", "c");
        assertEquals(0, first.getNodeNo().intValue());
        assertEquals(1, first.getSignCount().intValue());
        assertEquals(0, first.getStatus().intValue());
        TItemChangeBill second = billService.approve(id, "wang", "c");
        assertEquals(1, second.getNodeNo().intValue());
        assertEquals(0, second.getSignCount().intValue());

        // 否决要落 status=2（旧代码空写一笔，状态停在原处）
        Long id2 = addBill(0, 0, null, 0, 0);
        TItemChangeBill veto = billService.reject(id2, "boss", "no");
        assertEquals(2, veto.getStatus().intValue());
        assertEquals(Integer.valueOf(2), billMapper.selectById(id2).getStatus());
        assertNull(billService.approve(id2, "late", "c"));

        // 头道无处可退
        Long id3 = addBill(0, 0, null, 0, 0);
        assertNull(billService.rollback(id3, "r"));
        billService.approve(id3, "z", "c");
        TItemChangeBill back = billService.rollback(id3, "r");
        assertEquals(0, back.getNodeNo().intValue());
        assertEquals(0, back.getSignCount().intValue());
    }

    // ---------------- 配置卡 ----------------

    @Test
    @DisplayName("推进逐态守卫、status缺值不NPE、每档生成核验串；退回只许1→0且不清串")
    void cardAdvanceGuardAndVerifyTrail() {
        Long id = addCard(null, "B1", 0); // 存量单状态缺着
        int r1 = cardService.advanceTItemImplementCard(id);
        assertEquals(1, r1);
        TItemImplementCard after1 = cardMapper.selectById(id);
        assertEquals(Integer.valueOf(1), after1.getStatus());
        assertNotNull(after1.getContent());
        String v1 = after1.getContent();
        // 已核对再推到冻住，核验串随版次换
        cardService.advanceTItemImplementCard(id);
        TItemImplementCard after2 = cardMapper.selectById(id);
        assertEquals(Integer.valueOf(2), after2.getStatus());
        assertFalse(v1.equals(after2.getContent()));
        // 冻住拒推
        assertEquals(0, cardService.advanceTItemImplementCard(id));
        // 冻住拒退
        assertEquals(0, cardService.revertTItemImplementCard(id));

        // 另一张：待核对拒退
        Long id2 = addCard(0, "B2", 0);
        assertEquals(0, cardService.revertTItemImplementCard(id2));
        cardService.advanceTItemImplementCard(id2);
        int rev = cardService.revertTItemImplementCard(id2);
        assertEquals(1, rev);
        TItemImplementCard reverted = cardMapper.selectById(id2);
        assertEquals(Integer.valueOf(0), reverted.getStatus());
        // 旧核验串沉底留痕，不被清掉
        assertNotNull(reverted.getContent());
    }

    @Test
    @DisplayName("明细列表尊重传入条件且不偷塞分页：能查全部状态，记录总数不受startPage截断")
    void cardListHonoursConditionAndNoForcedPaging() {
        addCard(0, "C1", 0);
        addCard(1, "C2", 0);
        addCard(2, "C3", 0);
        // 服务层旧代码硬塞 status=0 且 startPage(1,10)：现在三条（含已核对/已冻住）都点得着
        List<TItemImplementCard> all = cardService
                .selectTItemImplementCardList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TItemImplementCard>());
        assertEquals(3, all.size());

        List<TItemImplementCard> onlyFrozen = cardService.selectTItemImplementCardList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TItemImplementCard>()
                        .eq("status", 2));
        assertEquals(1, onlyFrozen.size());
        assertEquals("C3", onlyFrozen.get(0).getBillNo());
    }

    // ---------------- 造数与表 ----------------

    private TItemCatalogRow row(String itemCode, BigDecimal qty, int reason) {
        TItemCatalogRow r = new TItemCatalogRow();
        r.setItemCode(itemCode);
        r.setQty(qty);
        r.setReasonType(reason);
        r.setDueAt(t("2026-06-30 17:00:00"));
        r.setStatus(0);
        r.setDelFlag(0);
        return r;
    }

    private void addRule(String code, String name, Integer th1, Integer th2, Integer th3,
                         Date start, Date end, int status, int priority) {
        TItemAlertRule r = new TItemAlertRule();
        r.setRuleCode(code);
        r.setRuleName(name);
        r.setTh1Max(th1 == null ? null : new BigDecimal(th1));
        r.setTh2Max(th2 == null ? null : new BigDecimal(th2));
        r.setTh3Max(th3 == null ? null : new BigDecimal(th3));
        r.setEffStart(start);
        r.setEffEnd(end);
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(0);
        ruleMapper.insert(r);
    }

    private Long idOfRule(String code) {
        return ruleMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TItemAlertRule>()
                .eq("rule_code", code)).getId();
    }

    private Long addVer(int stage, int status) {
        TItemCatalogVer v = new TItemCatalogVer();
        v.setBizNo("V-" + System.nanoTime());
        v.setStage(stage);
        v.setStatus(status);
        v.setDelFlag(0);
        verMapper.insert(v);
        return v.getId();
    }

    private Long addBill(int node, int signMode, Integer need, int signCount, int status) {
        TItemChangeBill b = new TItemChangeBill();
        b.setBillNo("BL-" + System.nanoTime());
        b.setNodeNo(node);
        b.setSignMode(signMode);
        b.setNeedCount(need);
        b.setSignCount(signCount);
        b.setStatus(status);
        b.setDelFlag(0);
        billMapper.insert(b);
        return b.getId();
    }

    private Long addCard(Integer status, String billNo, int siteId) {
        TItemImplementCard c = new TItemImplementCard();
        c.setBillNo(billNo);
        c.setSiteId(siteId);
        c.setSiteNo("ZW00");
        c.setQty(new BigDecimal("100"));
        c.setFineAmt(new BigDecimal("7"));
        c.setStatus(status);
        c.setDelFlag(0);
        cardMapper.insert(c);
        return c.getId();
    }

    private void seedArch(long id, String no, int status) {
        TItemDeptArch a = new TItemDeptArch();
        a.setId(id);
        a.setSiteNo(no);
        a.setSiteName("在编部门");
        a.setStatus(status);
        a.setDelFlag(0);
        archMapper.insert(a);
    }

    private static void schema(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t_item_alert_rule ("
                    + "id bigint PRIMARY KEY, rule_code varchar(64), rule_name varchar(128), "
                    + "th1_max decimal(12,2), th2_max decimal(12,2), th3_max decimal(12,2), "
                    + "eff_start datetime, eff_end datetime, priority int, status int, del_flag int, "
                    + "create_by varchar(64), create_time datetime, update_by varchar(64), "
                    + "update_time datetime, remark varchar(500))");
            st.execute("CREATE TABLE t_item_catalog_row ("
                    + "id bigint PRIMARY KEY, batch_no varchar(64), row_no int, "
                    + "item_code varchar(64), site_no varchar(64), reason_type int, "
                    + "due_at datetime, amount decimal(12,2), qty decimal(12,2), status int, "
                    + "del_flag int, create_by varchar(64), create_time datetime, "
                    + "update_by varchar(64), update_time datetime, remark varchar(500))");
            st.execute("CREATE TABLE t_item_catalog_ver ("
                    + "id bigint PRIMARY KEY, biz_no varchar(64), stage int, status int, "
                    + "content varchar(255), last_action varchar(64), del_flag int, "
                    + "create_by varchar(64), create_time datetime, update_by varchar(64), "
                    + "update_time datetime, remark varchar(500))");
            st.execute("CREATE TABLE t_item_change_bill ("
                    + "id bigint PRIMARY KEY, bill_no varchar(64), node_no int, sign_mode int, "
                    + "need_count int, sign_count int, status int, del_flag int, "
                    + "create_by varchar(64), create_time datetime, update_by varchar(64), "
                    + "update_time datetime, remark varchar(500))");
            st.execute("CREATE TABLE t_item_implement_card ("
                    + "id bigint PRIMARY KEY, bill_no varchar(64), node_no int, site_id int, "
                    + "site_no varchar(64), qty decimal(12,2), fine_amt decimal(12,2), "
                    + "content varchar(255), grade_level int, status int, del_flag int, "
                    + "create_by varchar(64), create_time datetime, update_by varchar(64), "
                    + "update_time datetime, remark varchar(500))");
            st.execute("CREATE TABLE t_item_dept_arch ("
                    + "id bigint PRIMARY KEY, site_no varchar(64), site_name varchar(128), "
                    + "site_type varchar(32), road_name varchar(128), "
                    + "th1_max decimal(12,2), th2_max decimal(12,2), th3_max decimal(12,2), "
                    + "status int, del_flag int, create_by varchar(64), create_time datetime, "
                    + "update_by varchar(64), update_time datetime, remark varchar(500))");
        }
    }
}
