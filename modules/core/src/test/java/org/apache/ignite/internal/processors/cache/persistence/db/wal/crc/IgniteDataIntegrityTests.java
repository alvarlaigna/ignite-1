/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.persistence.db.wal.crc;

import junit.framework.TestCase;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ignite.internal.processors.cache.persistence.file.RandomAccessFileIO;
import org.apache.ignite.internal.processors.cache.persistence.wal.ByteBufferExpander;
import org.apache.ignite.internal.processors.cache.persistence.wal.FileInput;
import org.apache.ignite.internal.processors.cache.persistence.wal.crc.IgniteDataIntegrityViolationException;
import org.apache.ignite.internal.processors.cache.persistence.wal.crc.PureJavaCrc32;

/**
 *
 */
public class IgniteDataIntegrityTests extends TestCase {
    /** File input. */
    private FileInput fileInput;

    /** Random access file. */
    private RandomAccessFile randomAccessFile;

    /** Buffer expander. */
    private ByteBufferExpander expBuf;

    /** {@inheritDoc} */
    @Override protected void setUp() throws Exception {
        super.setUp();

        File file = File.createTempFile("integrity", "dat");
        file.deleteOnExit();

        randomAccessFile = new RandomAccessFile(file, "rw");

        expBuf = new ByteBufferExpander(1024, ByteOrder.BIG_ENDIAN);

        fileInput = new FileInput(
            new RandomAccessFileIO(randomAccessFile),
            expBuf
        );

        ByteBuffer buf = ByteBuffer.allocate(1024);
        ThreadLocalRandom curr = ThreadLocalRandom.current();

        for (int i = 0; i < 1024; i+=16) {
            buf.putInt(curr.nextInt());
            buf.putInt(curr.nextInt());
            buf.putInt(curr.nextInt());
            buf.position(i);
            buf.putInt(PureJavaCrc32.calcCrc32(buf, 12));
        }

        randomAccessFile.write(buf.array());
        randomAccessFile.getFD().sync();
    }

    /** {@inheritDoc} */
    @Override protected void tearDown() throws Exception {
        randomAccessFile.close();
        expBuf.close();
    }

    /**
     *
     */
    public void testSuccessfulPath() throws Exception {
        checkIntegrity();
    }

    /**
     *
     */
    public void testIntegrityViolationChecking() throws Exception {
        toggleOneRandomBit(0, 1024 - 16);

        try {
            checkIntegrity();

            fail();
        } catch (IgniteDataIntegrityViolationException ex) {
            //success
        }
    }

    /**
     *
     */
    public void testSkipingLastCorruptedEntry() throws Exception {
        toggleOneRandomBit(1024 - 16, 1024);

        try {
            checkIntegrity();

            fail();
        } catch (EOFException ex) {
            //success
        }
    }

    /**
     *
     */
    public void testExpandBuffer() {
        ByteBufferExpander expBuf = new ByteBufferExpander(16, ByteOrder.nativeOrder());

        ByteBuffer b1 = expBuf.buffer();

        b1.put((byte)1);
        b1.putInt(2);
        b1.putLong(3L);

        assertEquals(13, b1.position());
        assertEquals(16, b1.limit());

        ByteBuffer b2 = expBuf.expand(32);

        assertEquals(0, b2.position());
        assertEquals((byte)1, b2.get());
        assertEquals(2, b2.getInt());
        assertEquals(3L, b2.getLong());
        assertEquals(13, b2.position());
        assertEquals(32, b2.limit());

        b2.putInt(4);

        assertEquals(17, b2.position());
        assertEquals(32, b2.limit());

        b2.flip();

        assertEquals(0, b2.position());
        assertEquals((byte)1, b2.get());
        assertEquals(2, b2.getInt());
        assertEquals(3L, b2.getLong());
        assertEquals(4, b2.getInt());
        assertEquals(17, b2.limit());
    }

    /**
     * @param rangeFrom Range from.
     * @param rangeTo Range to.
     */
    private void toggleOneRandomBit(int rangeFrom, int rangeTo) throws IOException {
        int pos = ThreadLocalRandom.current().nextInt(rangeFrom, rangeTo);
        randomAccessFile.seek(pos);

        byte b = randomAccessFile.readByte();

        b ^=  (1 << 3);

        randomAccessFile.seek(pos);
        randomAccessFile.writeByte(b);
        randomAccessFile.getFD().sync();
    }

    /**
     *
     */
    private void checkIntegrity() throws Exception {
        randomAccessFile.seek(0);

        for (int i = 0; i < 1024 / 16; i++) {
            try(FileInput.Crc32CheckingFileInput in = fileInput.startRead(false)) {
                in.readInt();
                in.readInt();
                in.readInt();
            }
        }
    }
}
