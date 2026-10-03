package com.fc.v2.item;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fc.v2.mapper.auto.TItemChangeBillMapper;
import com.fc.v2.model.auto.TItemChangeBill;
import com.fc.v2.service.impl.TItemChangeBillServiceImpl;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐级核签：OR 一票进道次、AND 两名点齐、reject 真写回、退回守边界、终态封口。
 */
class ChangeBillServiceTest {

    private SqlSession session;
    private TItemChangeBillMapper mapper;
    private TItemChangeBillServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:bill_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t_item_change_bill ("
                    + "id bigint PRIMARY KEY, bill_no varchar(64), node_no int, sign_mode int, "
                    + "need_count int, sign_count int, status int, del_flag int, "
                    + "create_by varchar(64), create_time datetime, update_by varchar(64), "
                    + "update_time datetime, remark varchar(500))");
        }
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        GlobalConfig gc = new GlobalConfig();
        gc.setDbConfig(new GlobalConfig.DbConfig());
        cfg.addMapper(TItemChangeBillMapper.class);
        MybatisSqlSessionFactoryBean fb = new MybatisSqlSessionFactoryBean();
        fb.setDataSource(ds);
        fb.setConfiguration(cfg);
        fb.setGlobalConfig(gc);
        SqlSessionFactory factory = fb.getObject();
        this.session = factory.openSession(true);
        this.mapper = session.getMapper(TItemChangeBillMapper.class);
        this.service = new TItemChangeBillServiceImpl();
        Field f = TItemChangeBillServiceImpl.class.getDeclaredField("itemChangeBillMapper");
        f.setAccessible(true);
        f.set(service, mapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private TItemChangeBill bill(long id, int signMode, Integer need) {
        TItemChangeBill b = new TItemChangeBill();
        b.setId(id);
        b.setBillNo("B" + id);
        b.setNodeNo(0);
        b.setSignMode(signMode);
        b.setNeedCount(need);
        b.setSignCount(0);
        b.setStatus(0);
        b.setDelFlag(0);
        mapper.insert(b);
        return b;
    }

    @Test
    @DisplayName("OR 任一人：一票即进下一环节，县核→市核→省定→核讫")
    void orModeOneVoteAdvances() {
        TItemChangeBill b = bill(1, 0, null);
        TItemChangeBill r1 = service.approve(b.getId(), "张三", "可");
        assertEquals(Integer.valueOf(1), r1.getNodeNo());
        assertEquals(Integer.valueOf(0), r1.getStatus());

        service.approve(b.getId(), "李四", null);
        TItemChangeBill r2 = service.approve(b.getId(), "王五", null);
        assertEquals(Integer.valueOf(3), r2.getNodeNo());
        // 过了省定（node>2）即核讫
        assertEquals(Integer.valueOf(1), r2.getStatus());
        // 终态不再受票
        assertNull(service.approve(b.getId(), "赵六", null));
    }

    @Test
    @DisplayName("AND 两名点齐：头一票留在本口计数，第二票才进道次")
    void andModeRequiresBoth() {
        TItemChangeBill b = bill(2, 1, 2);
        TItemChangeBill first = service.approve(b.getId(), "张三", null);
        assertEquals(Integer.valueOf(0), first.getNodeNo());
        assertEquals(Integer.valueOf(1), first.getSignCount());
        assertEquals(Integer.valueOf(0), first.getStatus());

        TItemChangeBill second = service.approve(b.getId(), "李四", null);
        assertEquals(Integer.valueOf(1), second.getNodeNo());
        assertEquals(Integer.valueOf(0), second.getSignCount());
    }

    @Test
    @DisplayName("处置了状态要写回：否决落成已打回且留下意见；打回后不能再签")
    void rejectWritesStatusAndComment() {
        TItemChangeBill b = bill(3, 0, null);
        TItemChangeBill r = service.reject(b.getId(), "张三", "材料不实");
        assertEquals(Integer.valueOf(2), r.getStatus());
        assertTrue(r.getRemark().contains("材料不实"));
        // 档里真落了，不是只 update 空壳
        assertEquals(Integer.valueOf(2), mapper.selectById(b.getId()).getStatus());
        assertNull(service.approve(b.getId(), "李四", null));
    }

    @Test
    @DisplayName("退回上一环节：从市核退回县核；县核无处可退")
    void rollbackGuardsFirstNode() {
        TItemChangeBill b = bill(4, 0, null);
        service.approve(b.getId(), "张三", null);   // 到市核 node=1
        TItemChangeBill back = service.rollback(b.getId(), "补材料");
        assertEquals(Integer.valueOf(0), back.getNodeNo());
        assertNull(service.rollback(b.getId(), "再退"));
    }

    @Test
    @DisplayName("缺落名人/查无单不收票")
    void blankApproverRejected() {
        TItemChangeBill b = bill(5, 0, null);
        assertNull(service.approve(b.getId(), "  ", null));
        assertNull(service.approve(999L, "张三", null));
        assertEquals(Integer.valueOf(0), mapper.selectById(b.getId()).getNodeNo());
    }
}
