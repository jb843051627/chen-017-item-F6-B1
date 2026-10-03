package com.fc.v2.item;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fc.v2.mapper.auto.TItemCatalogVerMapper;
import com.fc.v2.model.auto.TItemCatalogVer;
import com.fc.v2.service.impl.TItemCatalogVerServiceImpl;
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

/**
 * 版次单过口：逐档推进/回退、到顶与作废封口、软删留底。
 */
class CatalogVerServiceTest {

    private SqlSession session;
    private TItemCatalogVerMapper mapper;
    private TItemCatalogVerServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ver_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t_item_catalog_ver ("
                    + "id bigint PRIMARY KEY, biz_no varchar(64), stage int, status int, "
                    + "content varchar(255), last_action varchar(64), del_flag int, "
                    + "create_by varchar(64), create_time datetime, update_by varchar(64), "
                    + "update_time datetime, remark varchar(500))");
        }
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        GlobalConfig gc = new GlobalConfig();
        gc.setDbConfig(new GlobalConfig.DbConfig());
        cfg.addMapper(TItemCatalogVerMapper.class);
        MybatisSqlSessionFactoryBean fb = new MybatisSqlSessionFactoryBean();
        fb.setDataSource(ds);
        fb.setConfiguration(cfg);
        fb.setGlobalConfig(gc);
        SqlSessionFactory factory = fb.getObject();
        this.session = factory.openSession(true);
        this.mapper = session.getMapper(TItemCatalogVerMapper.class);
        this.service = new TItemCatalogVerServiceImpl();
        Field f = TItemCatalogVerServiceImpl.class.getDeclaredField("itemCatalogVerMapper");
        f.setAccessible(true);
        f.set(service, mapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private TItemCatalogVer ver(long id, Integer stage, Integer status) {
        TItemCatalogVer v = new TItemCatalogVer();
        v.setId(id);
        v.setBizNo("V" + id);
        v.setStage(stage);
        v.setStatus(status);
        v.setDelFlag(0);
        mapper.insert(v);
        return v;
    }

    @Test
    @DisplayName("推进逐档走：0→1→2→3，一档一挪，不跳档")
    void advanceOneStageAtATime() {
        TItemCatalogVer v = ver(1, 0, 1);
        assertEquals(Integer.valueOf(1), service.advance(v.getId(), "过复核").getStage());
        assertEquals(Integer.valueOf(2), service.advance(v.getId(), null).getStage());
        assertEquals(Integer.valueOf(3), service.advance(v.getId(), null).getStage());
        // 到顶不再推进
        assertNull(service.advance(v.getId(), null));
        assertEquals(Integer.valueOf(3), mapper.selectById(v.getId()).getStage());
    }

    @Test
    @DisplayName("回退逐档走：只退一档；草拟无档可退")
    void rollbackOneStageAtATime() {
        TItemCatalogVer v = ver(2, 2, 1);
        assertEquals(Integer.valueOf(1), service.rollback(v.getId(), null).getStage());
        assertEquals(Integer.valueOf(0), service.rollback(v.getId(), null).getStage());
        assertNull(service.rollback(v.getId(), null));
    }

    @Test
    @DisplayName("已作废两头不动")
    void terminalLocked() {
        TItemCatalogVer v = ver(3, 1, 2);
        assertNull(service.advance(v.getId(), null));
        assertNull(service.rollback(v.getId(), null));
        assertEquals(Integer.valueOf(1), mapper.selectById(v.getId()).getStage());
    }

    @Test
    @DisplayName("存量空档按草拟起步")
    void nullStageTreatedAsDraft() {
        TItemCatalogVer v = ver(4, null, null);
        assertEquals(Integer.valueOf(1), service.advance(v.getId(), null).getStage());
    }

    @Test
    @DisplayName("删除只落标记留底，再查不出来")
    void softDeleteKeepsRow() {
        TItemCatalogVer v = ver(5, 1, 1);
        assertEquals(true, service.remove(v.getId()));
        assertEquals(Integer.valueOf(1), mapper.selectById(v.getId()).getDelFlag());
        assertNull(service.selectTItemCatalogVerById(v.getId()));
    }
}
