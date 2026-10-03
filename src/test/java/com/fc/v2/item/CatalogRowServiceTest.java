package com.fc.v2.item;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fc.v2.mapper.auto.TItemCatalogRowMapper;
import com.fc.v2.model.auto.TItemCatalogRow;
import com.fc.v2.service.impl.TItemCatalogRowServiceImpl;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上级目录下发册整批提交：空册不炸、超限整批退、缺值不连坐、同册不重入。
 */
class CatalogRowServiceTest {

    private SqlSession session;
    private TItemCatalogRowMapper mapper;
    private TItemCatalogRowServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:row_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t_item_catalog_row ("
                    + "id bigint PRIMARY KEY, batch_no varchar(64), row_no int, "
                    + "item_code varchar(64), site_no varchar(64), reason_type int, "
                    + "due_at datetime, amount decimal(12,2), qty decimal(12,2), status int, "
                    + "del_flag int, create_by varchar(64), create_time datetime, "
                    + "update_by varchar(64), update_time datetime, remark varchar(500))");
        }
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        GlobalConfig gc = new GlobalConfig();
        gc.setDbConfig(new GlobalConfig.DbConfig());
        cfg.addMapper(TItemCatalogRowMapper.class);
        MybatisSqlSessionFactoryBean fb = new MybatisSqlSessionFactoryBean();
        fb.setDataSource(ds);
        fb.setConfiguration(cfg);
        fb.setGlobalConfig(gc);
        SqlSessionFactory factory = fb.getObject();
        this.session = factory.openSession(true);
        this.mapper = session.getMapper(TItemCatalogRowMapper.class);
        this.service = new TItemCatalogRowServiceImpl();
        Field f = TItemCatalogRowServiceImpl.class.getDeclaredField("itemCatalogRowMapper");
        f.setAccessible(true);
        f.set(service, mapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private TItemCatalogRow row(String code, int rowNo, String qty) {
        TItemCatalogRow r = new TItemCatalogRow();
        r.setRowNo(rowNo);
        r.setItemCode(code);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        r.setStatus(0);
        r.setDelFlag(0);
        return r;
    }

    @Test
    @DisplayName("空情形接得住：null 与空册都回 0，不抛错")
    void emptyBatchIsZeroNotError() {
        assertEquals(0, service.submitBatch("B", null));
        assertEquals(0, service.submitBatch("B", new ArrayList<TItemCatalogRow>()));
        assertEquals(0, mapper.selectList(null).size());
    }

    @Test
    @DisplayName("一条缺值不搅断整轮：好行落库成功、坏行进失败明细且原行号带着")
    void invalidRowDoesNotStopOthers() {
        List<TItemCatalogRow> rows = new ArrayList<>();
        rows.add(row("ITEM-A", 1, "3"));        // 好
        rows.add(row(null, 2, "3"));            // 事项码缺
        rows.add(row("ITEM-C", 3, "0"));        // 项数非正
        rows.add(row("ITEM-D", 4, null));       // 项数缺
        rows.add(null);                          // 坑位空着

        int ok = service.submitBatch("B1", rows);
        assertEquals(1, ok);

        List<TItemCatalogRow> all = mapper.selectList(null);
        assertEquals(4, all.size());  // null 坑位不计
        List<TItemCatalogRow> errors = service.listErrors("B1");
        assertEquals(3, errors.size());
        // 原行号升序
        assertEquals(Integer.valueOf(2), errors.get(0).getRowNo());
        assertEquals(Integer.valueOf(3), errors.get(1).getRowNo());
        assertEquals(Integer.valueOf(4), errors.get(2).getRowNo());
        assertTrue(errors.get(0).getStatus() == 2);
    }

    @Test
    @DisplayName("处置过的不翻出来重处置：同册次同行号再交不重复入库")
    void sameBatchNotReinserted() {
        List<TItemCatalogRow> rows = new ArrayList<>();
        rows.add(row("ITEM-A", 1, "3"));
        assertEquals(1, service.submitBatch("B2", rows));

        List<TItemCatalogRow> again = new ArrayList<>();
        again.add(row("ITEM-A", 1, "3"));   // 同一册同一行
        again.add(row("ITEM-B", 2, "4"));   // 新行
        int ok = service.submitBatch("B2", again);
        assertEquals(1, ok);
        assertEquals(2, mapper.selectList(null).size());
    }

    @Test
    @DisplayName("超单批上限整批退回 -1，一笔都不入库")
    void overLimitRejectsWholeBatch() {
        List<TItemCatalogRow> rows = new ArrayList<>();
        for (int i = 1; i <= 501; i++) {
            rows.add(row("ITEM-" + i, i, "1"));
        }
        assertEquals(-1, service.submitBatch("B3", rows));
        assertEquals(0, mapper.selectList(null).size());
    }
}
