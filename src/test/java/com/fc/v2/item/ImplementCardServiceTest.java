package com.fc.v2.item;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fc.v2.mapper.auto.TItemDeptArchMapper;
import com.fc.v2.mapper.auto.TItemImplementCardMapper;
import com.fc.v2.model.auto.TItemDeptArch;
import com.fc.v2.model.auto.TItemImplementCard;
import com.fc.v2.service.impl.TItemImplementCardServiceImpl;
import com.fc.v2.util.MD5Util;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实施清单配置卡：列表口径单一、推进/退回逐态、校验串随版次沉底、新增修改同尺。
 */
class ImplementCardServiceTest {

    private SqlSession session;
    private TItemImplementCardMapper cardMapper;
    private TItemDeptArchMapper archMapper;
    private TItemImplementCardServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:card_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
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
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        GlobalConfig gc = new GlobalConfig();
        gc.setDbConfig(new GlobalConfig.DbConfig());
        cfg.addMapper(TItemImplementCardMapper.class);
        cfg.addMapper(TItemDeptArchMapper.class);
        MybatisSqlSessionFactoryBean fb = new MybatisSqlSessionFactoryBean();
        fb.setDataSource(ds);
        fb.setConfiguration(cfg);
        fb.setGlobalConfig(gc);
        SqlSessionFactory factory = fb.getObject();
        this.session = factory.openSession(true);
        this.cardMapper = session.getMapper(TItemImplementCardMapper.class);
        this.archMapper = session.getMapper(TItemDeptArchMapper.class);

        seedArch(0, "ZW00", 0);
        seedArch(1, "ZW01", 1);

        this.service = new TItemImplementCardServiceImpl();
        setField(service, "baseMapper", cardMapper);
        setField(service, "itemDeptArchMapper", archMapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignore) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void seedArch(long id, String no, int status) {
        TItemDeptArch a = new TItemDeptArch();
        a.setId(id);
        a.setSiteNo(no);
        a.setSiteName("部门" + no);
        a.setStatus(status);
        a.setDelFlag(0);
        archMapper.insert(a);
    }

    private TItemImplementCard card(long id, String billNo, int siteId, Integer status) {
        TItemImplementCard c = new TItemImplementCard();
        c.setId(id);
        c.setBillNo(billNo);
        c.setSiteId(siteId);
        c.setSiteNo(siteId == 0 ? "ZW00" : "ZW01");
        c.setQty(new BigDecimal("1200"));
        c.setFineAmt(new BigDecimal("10"));
        c.setNodeNo(0);
        c.setStatus(status);
        c.setDelFlag(0);
        cardMapper.insert(c);
        return c;
    }

    @Test
    @DisplayName("明细与下载同一把尺：三种状态都查得出，不再被硬塞成只看待核对、也不被内部截断")
    void listKeepsAllStatusesAndCallerFilter() {
        card(1, "C1", 0, 0);
        card(2, "C2", 0, 1);
        card(3, "C3", 0, 2);

        List<TItemImplementCard> all =
                service.selectTItemImplementCardList(new QueryWrapper<TItemImplementCard>());
        assertEquals(3, all.size());

        // 调用方自己的条件照走（明细页筛"已冻住"）
        List<TItemImplementCard> frozen = service.selectTItemImplementCardList(
                new QueryWrapper<TItemImplementCard>().eq("status", 2));
        assertEquals(1, frozen.size());
        assertEquals("C3", frozen.get(0).getBillNo());
    }

    @Test
    @DisplayName("推进逐态+空状态兼容：null→已核对→已冻住，到顶不再动")
    void advanceStatesAndNullSafe() {
        TItemImplementCard c = card(10, "C10", 0, null);
        assertEquals(1, service.advanceTItemImplementCard(c.getId()));
        TItemImplementCard one = cardMapper.selectById(c.getId());
        assertEquals(Integer.valueOf(1), one.getStatus());
        assertEquals(Integer.valueOf(1), one.getNodeNo());

        assertEquals(1, service.advanceTItemImplementCard(c.getId()));
        TItemImplementCard two = cardMapper.selectById(c.getId());
        assertEquals(Integer.valueOf(2), two.getStatus());
        assertEquals(Integer.valueOf(2), two.getNodeNo());
        // 到顶
        assertEquals(0, service.advanceTItemImplementCard(c.getId()));
    }

    @Test
    @DisplayName("校验串随版次生成；退回只退态、旧串沉底不清")
    void checksumGeneratedAndKeptOnRevert() {
        TItemImplementCard c = card(20, "C20", 0, 0);
        service.advanceTItemImplementCard(c.getId());
        TItemImplementCard one = cardMapper.selectById(c.getId());
        assertNotNull(one.getContent());
        // decimal(12,2) 读回带刻度 1200.00/10.00，与档里真实值同口径算串
        String expect = MD5Util.encode("C20|1|ZW00|1200.00|10.00");
        assertEquals(expect, one.getContent());

        // 已核对→待核对，旧版校验串仍在
        assertEquals(1, service.revertTItemImplementCard(c.getId()));
        TItemImplementCard reverted = cardMapper.selectById(c.getId());
        assertEquals(Integer.valueOf(0), reverted.getStatus());
        assertEquals(expect, reverted.getContent());
    }

    @Test
    @DisplayName("退回守边界：待核对无处可退、已冻住不许退")
    void revertGuarded() {
        TItemImplementCard pending = card(30, "C30", 0, 0);
        assertEquals(0, service.revertTItemImplementCard(pending.getId()));

        TItemImplementCard frozen = card(31, "C31", 0, 2);
        assertEquals(0, service.revertTItemImplementCard(frozen.getId()));
        assertEquals(Integer.valueOf(2), cardMapper.selectById(frozen.getId()).getStatus());
    }

    @Test
    @DisplayName("新增与修改同一把尺：撤并部门/查无底册两处都不收")
    void insertAndUpdateSameArchGuard() {
        TItemImplementCard merged = new TItemImplementCard();
        merged.setId(40L);
        merged.setBillNo("C40");
        merged.setSiteId(1);  // 已撤并
        merged.setStatus(0);
        assertEquals(0, service.insertTItemImplementCard(merged));

        TItemImplementCard c = card(41, "C41", 0, 0);
        // 改挂到撤并部门，不收
        TItemImplementCard move = new TItemImplementCard();
        move.setId(c.getId());
        move.setSiteId(1);
        assertEquals(0, service.updateTItemImplementCard(move));
        // 原部门没被动
        assertEquals("ZW00", cardMapper.selectById(c.getId()).getSiteNo());

        // 同号重复新增不收
        TItemImplementCard dup = new TItemImplementCard();
        dup.setId(42L);
        dup.setBillNo("C41");
        dup.setSiteId(0);
        dup.setStatus(0);
        assertEquals(0, service.insertTItemImplementCard(dup));
    }

    @Test
    @DisplayName("正常新增带入部门代号、缺省待核对")
    void normalInsertCarriesSiteNo() {
        TItemImplementCard c = new TItemImplementCard();
        c.setId(50L);
        c.setBillNo("C50");
        c.setSiteId(0);
        c.setQty(new BigDecimal("300"));
        assertTrue(service.insertTItemImplementCard(c) > 0);
        TItemImplementCard back = cardMapper.selectById(50L);
        assertEquals("ZW00", back.getSiteNo());
        assertEquals(Integer.valueOf(0), back.getStatus());
        assertNull(back.getContent());
    }
}
