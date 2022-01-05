package com.wizbl.core.db;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

// TEST CLEAR
@Slf4j
public class BlockStoreTest {

    private static final String dbPath = "output-blockStore-test";
    private static final Brte2ApplicationContext context;

    static {
        Args.setParam(new String[]{"--output-directory", dbPath},
                Constant.TEST_CONF);
        context = new Brte2ApplicationContext(DefaultConfig.class);
    }

    BlockStore blockStore;

    @Before
    public void init() {
        blockStore = context.getBean(BlockStore.class);
    }

    @After
    public void destroy() {
        Args.clearParam();
        context.destroy();
        FileUtil.deleteDir(new File(dbPath));
    }

    @Test
    public void testCreateBlockStore() {
    }
}
