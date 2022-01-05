/*
 * java-brte2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-brte2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.wizbl.core.config.args;

import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.Options;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;

// TEST CLEAR
public class StorageTest {

    private static final Storage storage;

    static {
        Args.setParam(new String[]{}, Constant.TEST_CONF);
        storage = Args.getInstance().getStorage();
    }

    @AfterClass
    public static void cleanup() {
        Args.clearParam();
        FileUtil.deleteDir(new File("test_path"));
    }

    @Test
    public void getDirectory() {
        Assert.assertEquals("database-acorn-localtest", storage.getDbDirectory());
        Assert.assertEquals("index-acorn-localtest", storage.getIndexDirectory());
    }

    // conf 파일의 storage.properties의 항목에 대한 설정을 해줘야 정상적인 테스트가 진행됨.
    @Test
    @Ignore
    public void getPath() {
        Assert.assertEquals("storage_directory_account", storage.getPathByDbName("account"));
        Assert.assertEquals("storage_directory_account_index", storage.getPathByDbName("account-index"));
        Assert.assertNull(storage.getPathByDbName("some_name_not_exists"));
    }

    // conf 파일의 storage.properties의 세부 항목별 option의 값에 대한 테스트임.
    // conf 파일의 storage.properties의 항목에 대한 설정을 해줘야 정상적인 테스트가 진행됨.
    @Test
    @Ignore
    public void getOptions() {
        Options options = storage.getOptionsByDbName("account");
        Assert.assertTrue(options.createIfMissing());
        Assert.assertTrue(options.paranoidChecks());
        Assert.assertTrue(options.verifyChecksums());
        Assert.assertEquals(CompressionType.SNAPPY, options.compressionType());
        Assert.assertEquals(4096, options.blockSize());
        Assert.assertEquals(10485760, options.writeBufferSize());
        Assert.assertEquals(10485760L, options.cacheSize());
        Assert.assertEquals(100, options.maxOpenFiles());

        options = storage.getOptionsByDbName("account-index");
        Assert.assertFalse(options.createIfMissing());
        Assert.assertFalse(options.paranoidChecks());
        Assert.assertFalse(options.verifyChecksums());
        Assert.assertEquals(CompressionType.SNAPPY, options.compressionType());
        Assert.assertEquals(2, options.blockSize());
        Assert.assertEquals(3, options.writeBufferSize());
        Assert.assertEquals(4L, options.cacheSize());
        Assert.assertEquals(5, options.maxOpenFiles());

        options = storage.getOptionsByDbName("some_name_not_exists");
        Assert.assertTrue(options.createIfMissing());
        Assert.assertTrue(options.paranoidChecks());
        Assert.assertTrue(options.verifyChecksums());
        Assert.assertEquals(CompressionType.SNAPPY, options.compressionType());
        Assert.assertEquals(4 * 1024, options.blockSize());
        Assert.assertEquals(10 * 1024 * 1024, options.writeBufferSize());
        Assert.assertEquals(10 * 1024 * 1024L, options.cacheSize());
        Assert.assertEquals(100, options.maxOpenFiles());
    }

}
