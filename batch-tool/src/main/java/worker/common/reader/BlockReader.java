/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package worker.common.reader;

import com.lmax.disruptor.RingBuffer;
import model.ProducerExecutionContext;
import model.config.CompressMode;
import model.config.ConfigConstant;
import model.config.FileBlockListRecord;
import model.encrypt.BaseCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.FileUtil;
import util.IOUtil;
import worker.common.BatchLineEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

public class BlockReader extends FileBufferedBatchReader {

    private static class BlockByteBuffer {
        byte[] buffer;
        int len;

        BlockByteBuffer(int size) {
            this.buffer = new byte[size];
            this.len = 0;
        }

        int getCapacity() {
            return buffer.length;
        }

        void reload(byte[] data) {
            this.buffer = data;
            this.len = data.length;
        }
    }

    private static class BlockPosMarker {
        int curPos, curLen;

        public BlockPosMarker() {
            this.curPos = 0;
            this.curLen = 0;
        }

        int getReadingPos() {
            return curPos + curLen;
        }

        void reset() {
            this.curPos = 0;
            this.curLen = 0;
        }

        void resetPos(int newPos) {
            this.curPos = newPos;
            this.curLen = 0;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(BlockReader.class);

    private RandomAccessFile curRandomAccessFile;

    /**
     * ??????2MB
     */
    private final long readBlockSize;
    /**
     * 4KB
     * ???????????????????????????????????????
     */
    private static long READ_PADDING = 1024L * 4;
    private final BaseCipher cipher;
    private final FileBlockListRecord fileBlockListRecord;

    private final BlockByteBuffer byteBuffer;
    private final BlockPosMarker posMarker;
    private final byte[] gzipBuffer;

    public BlockReader(ProducerExecutionContext context,
                       FileBlockListRecord fileBlockListRecord,
                       RingBuffer<BatchLineEvent> ringBuffer, CompressMode compressMode)  {
        super(context, fileBlockListRecord.getFileList(), ringBuffer, compressMode);
        this.readBlockSize = context.getReadBlockSizeInMb() * 1024L * 1024;
        // set localProcessingFileIndex and startPosArr[localProcessingFileIndex]
        this.localProcessingFileIndex = fileBlockListRecord.getCurrentFileIndex().get();
        this.fileBlockListRecord = fileBlockListRecord;
        this.curRandomAccessFile = FileUtil.openRafForRead(fileList.get(localProcessingFileIndex));
        this.cipher = BaseCipher.getCipher(context.getEncryptionConfig(), false);
        if (this.compressMode != CompressMode.NONE) {
            this.gzipBuffer = new byte[(int) (readBlockSize + READ_PADDING)];
        } else {
            this.gzipBuffer = null;
        }
        this.byteBuffer = new BlockByteBuffer((int) (readBlockSize + READ_PADDING));
        this.posMarker = new BlockPosMarker();
    }

    @Override
    protected void readData() {
        int curReadingPos;
        while (true) {
            try {
                localProcessingBlockIndex = fileBlockListRecord.getStartPosArr()[localProcessingFileIndex].getAndIncrement();
                long pos = localProcessingBlockIndex * readBlockSize;
                // ???????????????block??????????????? : counter++
                context.getEventCounter().get(localProcessingFileIndex)
                    .putIfAbsent(localProcessingBlockIndex, new AtomicInteger(0));
                context.getEventCounter().get(localProcessingFileIndex)
                    .get(localProcessingBlockIndex).incrementAndGet();
                // ????????????????????????
                boolean skipFirst = (pos != 0);
                seekAndRead(pos);

                if (byteBuffer.len == -1) {
                    if (!nextFile()) {
                        // ???????????????????????????????????????, ??????
                        break;
                    }
                    continue;
                }
                preprocessBuffer();

                posMarker.reset();
                label_reading:
                while ((curReadingPos = posMarker.getReadingPos()) < byteBuffer.len) {
                    // ?????????
                    switch (byteBuffer.buffer[curReadingPos]) {
                    case '\n':
                        if (skipFirst) {
                            skipFirst = false;
                        } else if (pos == 0 && posMarker.curPos == 0 && context.isWithHeader()) {
                            // do nothing
                            // curPos will be updated after skip header
                        } else {
                            handleLine(pos == 0);
                        }

                        posMarker.resetPos(curReadingPos + 1);
                        if (posMarker.getReadingPos() > readBlockSize) {
                            // ?????????padding??? ??????
                            break label_reading;
                        }
                        break;
                    default:
                        posMarker.curLen++;
                    }
                }
                curReadingPos = posMarker.getReadingPos();
                // Dealing last line without '\n'.
                if (curReadingPos == byteBuffer.len && // Read till EOF.
                    curReadingPos <= readBlockSize) { // And not in padding.
                    // Dealing last line.
                    handleLine(pos == 0);
                }
                // ??????????????????block?????? : counter--
                context.getEventCounter().get(localProcessingFileIndex)
                    .get(localProcessingBlockIndex).getAndDecrement();
            } catch (Exception e) {
                e.printStackTrace();
                logger.error(e.getMessage());
                throw new RuntimeException(e);
            }
        }
        // ??????????????????
        if (bufferedLineCount != 0) {
            emitLineBuffer();
        }
    }

    private void handleLine(boolean checkBom) {
        int curReadingPos = posMarker.getReadingPos();
        byte[] tmpByteBuffer;
        if (curReadingPos - 1 >= 0 && byteBuffer.buffer[curReadingPos - 1] == '\r') {
            // handle \r\n
            tmpByteBuffer = new byte[posMarker.curLen - 1];
            System.arraycopy(byteBuffer.buffer, posMarker.curPos, tmpByteBuffer, 0, posMarker.curLen - 1);
        } else {
            tmpByteBuffer = new byte[posMarker.curLen];
            System.arraycopy(byteBuffer.buffer, posMarker.curPos, tmpByteBuffer, 0, posMarker.curLen);
        }
        int bytesEnd = tmpByteBuffer.length - 1, bytesOffset = 0;
        // remove BOM
        if (checkBom && bytesEnd >= 2 && context.isUtfCharset()) {
            if (tmpByteBuffer[0] == (byte) 0xEF && tmpByteBuffer[1] == (byte) 0xBB
                && tmpByteBuffer[2] == (byte) 0xBF) {
                bytesOffset = 3;
            }
        }
        // trim right
        while ((bytesEnd >= bytesOffset) && (tmpByteBuffer[bytesEnd] <= ' ')) {
            bytesEnd--;
        }
        if (bytesEnd < bytesOffset) {
            return;
        }

        String line = new String(tmpByteBuffer, bytesOffset, bytesEnd - bytesOffset + 1,
            context.getCharset());
        appendToLineBuffer(line);
    }

    private void seekAndRead(long pos) {
        try {
            curRandomAccessFile.seek(pos);
            byteBuffer.len = curRandomAccessFile.read(byteBuffer.buffer);
        } catch (IOException e) {
            logger.error(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * ????????????/??????????????????
     */
    private void preprocessBuffer() {
        try {
            if (this.compressMode == CompressMode.GZIP) {
                // ???buffer???????????????
                GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(byteBuffer.buffer),
                    ConfigConstant.DEFAULT_COMPRESS_BUFFER_SIZE);
                ByteArrayOutputStream gzipOutputBuffer = new ByteArrayOutputStream(byteBuffer.len * 2);
                int num = 0;
                while ((num = gzipInputStream.read(gzipBuffer, 0, byteBuffer.len)) != -1) {
                    gzipOutputBuffer.write(gzipBuffer, 0, num);
                }
                byteBuffer.reload(gzipOutputBuffer.toByteArray());
                gzipInputStream.close();
            }
            if (cipher != null) {
                byteBuffer.reload(cipher.decrypt(byteBuffer.buffer));
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private boolean nextFile() {
        if (fileBlockListRecord.getFileDoneList()[localProcessingFileIndex].compareAndSet(false, true)) {
            logger.info("{} ????????????", fileList.get(localProcessingFileIndex).getPath());
        }
        // ??????????????????block???????????????????????? : counter--
        context.getEventCounter().get(localProcessingFileIndex)
            .get(localProcessingBlockIndex).getAndDecrement();
        // ?????????????????????
        if (localProcessingFileIndex < fileList.size() - 1) {
            fileBlockListRecord.getCurrentFileIndex().compareAndSet(localProcessingFileIndex, localProcessingFileIndex + 1);
            // ???????????????????????? ???????????????????????????????????????
            localProcessingFileIndex++;
            localProcessingBlockIndex = -1;
            curRandomAccessFile = FileUtil.openRafForRead(fileList.get(localProcessingFileIndex));
            return true;
        }
        return false;
    }

    @Override
    protected void beforePublish() {
        context.getEmittedDataCounter().getAndIncrement();
        context.getEventCounter().get(localProcessingFileIndex).
            get(localProcessingBlockIndex).getAndIncrement();
    }

    @Override
    protected void close() {
        IOUtil.close(this.curRandomAccessFile);
    }
}
