package com.wizbl.core.db;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.VotesCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.protos.Protocol.Vote;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

// TEST CLEAR
@Slf4j
public class VotesStoreTest {

    private static final String dbPath = "output-votesStore-test";
    private static final Brte2ApplicationContext context;

    static {
        Args.setParam(new String[]{"-d", dbPath}, Constant.TEST_CONF);
        context = new Brte2ApplicationContext(DefaultConfig.class);
    }

    VotesStore votesStore;

    @AfterClass
    public static void destroy() {
        Args.clearParam();
        context.destroy();
        FileUtil.deleteDir(new File(dbPath));
    }

    @Before
    public void initDb() {
        this.votesStore = context.getBean(VotesStore.class);
    }

    @Test
    public void putAndGetVotes() {
        List<Vote> oldVotes = new ArrayList<Vote>();

        VotesCapsule votesCapsule = new VotesCapsule(ByteString.copyFromUtf8("100000000x"), oldVotes);
        this.votesStore.put(votesCapsule.createDbKey(), votesCapsule);

        Assert.assertTrue("votesStore is empyt", votesStore.iterator().hasNext());
        Assert.assertTrue(votesStore.has(votesCapsule.createDbKey()));
        VotesCapsule votesSource = this.votesStore
                .get(ByteString.copyFromUtf8("100000000x").toByteArray());
        Assert.assertEquals(votesCapsule.getAddress(), votesSource.getAddress());
        Assert.assertEquals(ByteString.copyFromUtf8("100000000x"), votesSource.getAddress());

//    votesCapsule = new VotesCapsule(ByteString.copyFromUtf8(""), oldVotes);
//    this.votesStore.put(votesCapsule.createDbKey(), votesCapsule);
//    votesSource = this.votesStore.get(ByteString.copyFromUtf8("").toByteArray());
//    Assert.assertEquals(votesStore.getAllVotes().size(), 2);
//    Assert.assertEquals(votesCapsule.getAddress(), votesSource.getAddress());
//    Assert.assertEquals(null, votesSource.getAddress());
    }
}