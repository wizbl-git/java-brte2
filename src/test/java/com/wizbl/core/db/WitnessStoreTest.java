package com.wizbl.core.db;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.WitnessCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

// TEST CLEAR
@Slf4j
public class WitnessStoreTest {

    private static final String dbPath = "output-witnessStore-test";
    private static final Brte2ApplicationContext context;

    static {
        Args.setParam(new String[]{"-d", dbPath}, Constant.TEST_CONF);
        context = new Brte2ApplicationContext(DefaultConfig.class);
    }

    WitnessStore witnessStore;

    @AfterClass
    public static void destroy() {
        Args.clearParam();
        context.destroy();
        FileUtil.deleteDir(new File(dbPath));
    }

    @Before
    public void initDb() {
        this.witnessStore = context.getBean(WitnessStore.class);
    }

    @Test
    public void putAndGetWitness() {
        WitnessCapsule witnessCapsule = new WitnessCapsule(ByteString.copyFromUtf8("100000000x"), 100L,
                "");

        this.witnessStore.put(witnessCapsule.getAddress().toByteArray(), witnessCapsule);
        WitnessCapsule witnessSource = this.witnessStore
                .get(ByteString.copyFromUtf8("100000000x").toByteArray());
        Assert.assertEquals(witnessCapsule.getAddress(), witnessSource.getAddress());
        Assert.assertEquals(witnessCapsule.getVoteCount(), witnessSource.getVoteCount());

        Assert.assertEquals(ByteString.copyFromUtf8("100000000x"), witnessSource.getAddress());
        Assert.assertEquals(100L, witnessSource.getVoteCount());

        witnessCapsule = new WitnessCapsule(ByteString.copyFromUtf8(""), 100L, "");

        this.witnessStore.put(witnessCapsule.getAddress().toByteArray(), witnessCapsule);
        witnessSource = this.witnessStore.get(ByteString.copyFromUtf8("").toByteArray());
        Assert.assertEquals(witnessCapsule.getAddress(), witnessSource.getAddress());
        Assert.assertEquals(witnessCapsule.getVoteCount(), witnessSource.getVoteCount());

        Assert.assertEquals(ByteString.copyFromUtf8(""), witnessSource.getAddress());
        Assert.assertEquals(100L, witnessSource.getVoteCount());
    }


}