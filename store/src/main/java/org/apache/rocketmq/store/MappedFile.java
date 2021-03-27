/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageExtBatch;
import org.apache.rocketmq.store.config.FlushDiskType;
import org.apache.rocketmq.store.util.LibC;
import sun.nio.ch.DirectBuffer;

/**
 * commitlog目录下的某一个文件 文件中第一条消息的全局物理偏移量是文件名
 *
 * 内存映射文件，myConfusionsv:用到了内存映射mmap()吗？--应该用到了，一般情况下（transientStorePoolEnable==false）只需要将数据写入
 * mappedByteBuffer就可以完成与磁盘文件的交互，而不需要再将数据从mappedByteBuffer写到内核文件缓冲区（其实这里的mappedByteBuffer就是内核态缓冲区了
 * --myConfusion:mappedByteBuffer是direct的，按理是由OS管理的内核态空间；但是mmap()的作用是用户态直接与磁盘交互，所以mappedByteBuffer应该是用户态）
 */
public class MappedFile extends ReferenceResource {

    // 操作系统每一页的大小 默认4KB
    public static final int OS_PAGE_SIZE = 1024 * 4;
    protected static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    // jvm中MappedFile虚拟内存(虚拟内存是计算机系统内存管理的一种技术。它使得应用程序认为它拥有连续可用的内存，而实际上，它通常是被分隔成多个
    // 物理内存碎片，还有部分暂时存储在外部磁盘存储器上，在需要时进行数据交换。)
    // Linux 实现了请求页面调度，页面调度是说页面从硬盘按需交换进来，当不再需要的时候交换出去。这样做允许系统中每个进程的虚拟地址空间和
    // 实际物理内存的总量再没有直接的联系，因为在硬盘上的交换空间能给进程一个物理内存几乎无限大的错觉。
    private static final AtomicLong TOTAL_MAPPED_VIRTUAL_MEMORY = new AtomicLong(0);

    // jvm中mappedFile个数
    private static final AtomicInteger TOTAL_MAPPED_FILES = new AtomicInteger(0);

    // 消息在当前MappedFile中的偏移量
    protected final AtomicInteger wrotePosition = new AtomicInteger(0);

    // 当前MappedFile的提交指针，提交到内存映射ByteBuffer中，提交到哪？ByteBuffer不也是属于当前MappedFile的吗？--是从writeBuffer写出到mappedByteBuffer--写到fileChannel
    // writeBuffer中的提交指针，之前的数据都以提交到fileChannel
    protected final AtomicInteger committedPosition = new AtomicInteger(0);
    // 刷到磁盘的指针，在这之前的数据都已刷到磁盘
    private final AtomicInteger flushedPosition = new AtomicInteger(0);

    // 文件最大容量
    protected int fileSize;
    // 与磁盘文件交互的channel
    protected FileChannel fileChannel;
    /**
     * 堆外内存，由transientStorePool分配 writeBuffer既不是内存映射buffer,也不是transientStorePool，那是啥，难道是所有写到内存映射buffer的数据都先要经过writeBuffer
     * 吗？--其实是transientStorePool的一部分，所以writeBuffer是transientStorePoolEnable为true时才不为空
     * Message will put to here first, and then reput to FileChannel if writeBuffer is not null.
     */
    protected ByteBuffer writeBuffer = null;
    // 堆外内存池 当MessageStoreConfig.transientStorePoolEnable==true时，数据先存到transientStorePool中，然后才到内存映射buffer
    // myConfusionsv:如果transientStorePoolEnable==false,数据可以直接写到mappedByteBuffer，而不需要经过writeBuffer的中转才到file缓冲区，那么transientStorePool到底有啥好处？
    // 1.因为appendMessagesInner时外层是加了putMessageLock的，且将数据写入到writeBuffer比到mappedByteBuffer中更快，因此为了使加锁时间变短，采用writeBuffer
    // 修改MappedByteBuffer实际会将数据写入文件对应的Page Cache中,Page Cache 页缓存是面向文件，面向内存的。通俗来说，它位于内存和文件之间缓冲区
    // 2. writeBuffer使用了内存锁定
    protected TransientStorePool transientStorePool = null;
    private String fileName;

    // 这是每个commitLog文件的偏移量？--初始物理偏移量
    private long fileFromOffset;
    // 物理磁盘文件
    private File file;
    // 内存映射buffer-物理文件file对应的 mappedByteBuffer类似于fileChannel的文件缓冲区
    private MappedByteBuffer mappedByteBuffer;
    // 文件最后一次写入时间戳
    private volatile long storeTimestamp = 0;
    // 是否为MappedFileQueue中的第一个文件
    private boolean firstCreateInQueue = false;

    public MappedFile() {
    }

    public MappedFile(final String fileName, final int fileSize) throws IOException {
        init(fileName, fileSize);
    }

    public MappedFile(final String fileName, final int fileSize,
        final TransientStorePool transientStorePool) throws IOException {
        init(fileName, fileSize, transientStorePool);
    }

    public static void ensureDirOK(final String dirName) {
        if (dirName != null) {
            File f = new File(dirName);
            if (!f.exists()) {
                boolean result = f.mkdirs();
                log.info(dirName + " mkdir " + (result ? "OK" : "Failed"));
            }
        }
    }

    /**
     * 调用cleaner.clean()释放堆外内存
     * @param buffer
     */
    public static void clean(final ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect() || buffer.capacity() == 0)
            return;
        invoke(invoke(viewed(buffer), "cleaner"), "clean");
    }

    private static Object invoke(final Object target, final String methodName, final Class<?>... args) {
        return AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                try {
                    Method method = method(target, methodName, args);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    private static Method method(Object target, String methodName, Class<?>[] args)
        throws NoSuchMethodException {
        try {
            return target.getClass().getMethod(methodName, args);
        } catch (NoSuchMethodException e) {
            return target.getClass().getDeclaredMethod(methodName, args);
        }
    }

    private static ByteBuffer viewed(ByteBuffer buffer) {
        String methodName = "viewedBuffer";
        Method[] methods = buffer.getClass().getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals("attachment")) {
                methodName = "attachment";
                break;
            }
        }

        ByteBuffer viewedBuffer = (ByteBuffer) invoke(buffer, methodName);
        if (viewedBuffer == null)
            return buffer;
        else
            return viewed(viewedBuffer);
    }

    public static int getTotalMappedFiles() {
        return TOTAL_MAPPED_FILES.get();
    }

    public static long getTotalMappedVirtualMemory() {
        return TOTAL_MAPPED_VIRTUAL_MEMORY.get();
    }

    /**
     * 初始化--transientStorePoolEnable==true时的
     * @param fileName
     * @param fileSize
     * @param transientStorePool
     * @throws IOException
     */
    public void init(final String fileName, final int fileSize,
        final TransientStorePool transientStorePool) throws IOException {
        init(fileName, fileSize);
        this.writeBuffer = transientStorePool.borrowBuffer();
        this.transientStorePool = transientStorePool;
    }

    private void init(final String fileName, final int fileSize) throws IOException {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.file = new File(fileName);
        // 文件名是第一条消息的物理偏移量 也是文件偏移量
        this.fileFromOffset = Long.parseLong(this.file.getName());
        boolean ok = false;

        ensureDirOK(this.file.getParent());

        try {
            this.fileChannel = new RandomAccessFile(this.file, "rw").getChannel();
            // jdk nio mappedByteBuffer是fileChannel对应的一块内存，mappedByteBuffer类似于fileChannel的内核缓冲区
            /**MappedByteBuffer的get方法最终通过DirectByteBuffer.get方法实现的。
             * map0()函数返回一个地址address，这样就无需调用read或write方法对文件进行读写，通过address就能够操作文件。
             * get方法底层采用unsafe.getByte方法，通过（address + 偏移量）获取指定内存的数据。
             第一次访问address所指向的内存区域，导致缺页中断，中断响应函数会在交换区中查找相对应的页面，如果找不到（也就是该文件从来没有被读入内存的情况），则从硬盘上将文件指定页读取到物理内存中（非jvm堆内存）。
             如果在拷贝数据时，发现物理内存不够用，则会通过虚拟内存机制（swap）将暂时不用的物理页面交换到硬盘的虚拟内存中。*/
            this.mappedByteBuffer = this.fileChannel.map(MapMode.READ_WRITE, 0, fileSize);
            TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(fileSize);
            TOTAL_MAPPED_FILES.incrementAndGet();
            ok = true;
        } catch (FileNotFoundException e) {
            log.error("Failed to create file " + this.fileName, e);
            throw e;
        } catch (IOException e) {
            log.error("Failed to map file " + this.fileName, e);
            throw e;
        } finally {
            if (!ok && this.fileChannel != null) {
                this.fileChannel.close();
            }
        }
    }

    public long getLastModifiedTimestamp() {
        return this.file.lastModified();
    }

    public int getFileSize() {
        return fileSize;
    }

    public FileChannel getFileChannel() {
        return fileChannel;
    }

    /**
     * msg追加到mappedFile中
     * @param msg
     * @param cb
     * @return
     */
    public AppendMessageResult appendMessage(final MessageExtBrokerInner msg, final AppendMessageCallback cb) {
        return appendMessagesInner(msg, cb);
    }

    public AppendMessageResult appendMessages(final MessageExtBatch messageExtBatch, final AppendMessageCallback cb) {
        return appendMessagesInner(messageExtBatch, cb);
    }

    /**
     * msg追加到mappedFile中--实际上是添加到writeBuffer或mappedByteBuffer中
     * @param messageExt
     * @param cb
     * @return
     */
    public AppendMessageResult appendMessagesInner(final MessageExt messageExt, final AppendMessageCallback cb) {
        assert messageExt != null;
        assert cb != null;

        // 1. 获取写指针
        int currentPos = this.wrotePosition.get();

        // 2. 判断是否大于当前文件大小
        if (currentPos < this.fileSize) {
            // 2.2 当前文件未写满，继续写入
            // 新建一个与writeBuffer共享内存区域，byteBuffer初始时pos lim cap分别为 0,cap,cap，因此slice()得到的buffer.pos/lim/cap分别是0/cap(mappedFileSize)/cap
            ByteBuffer byteBuffer = writeBuffer != null ? writeBuffer.slice() : this.mappedByteBuffer.slice();
            // myConfusionsv:currentPos难道不会超过limit（原lim-pos）吗？如果这个commitlog文件已经写了超过一半了，那么currentPos应该会超过limit吧
            // writeBuffer到底表示的啥？不是当前commitlog文件对应的buffer吗？--不是，writeBuffer其实是数据写到内存映射buffer的中转，cap是一个文件大小
            // currentPos之前的数据其实是之前已写进来的
            byteBuffer.position(currentPos);
            AppendMessageResult result;
            if (messageExt instanceof MessageExtBrokerInner) {
                // 数据写到byteBuffer，byteBuffer的position将递增，但是由于byteBuffer与writeBuffer的pos limit mark相互独立，
                // 因此writeBuffer的pos lim仍然是0，fileSize,所以当再次调用当前方法时，writeBuffer.slice()得到的byteBuffer需要指定pos(写入的指针)
                result = cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBrokerInner) messageExt);
            } else if (messageExt instanceof MessageExtBatch) {
                result = cb.doAppend(this.getFileFromOffset(), byteBuffer, this.fileSize - currentPos, (MessageExtBatch) messageExt);
            } else {
                return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
            }
            // wrotePosition加上已写入的字节数
            this.wrotePosition.addAndGet(result.getWroteBytes());
            this.storeTimestamp = result.getStoreTimestamp();
            return result;
        }
        // 2.1 当前文件已写满
        log.error("MappedFile.appendMessage return null, wrotePosition: {} fileSize: {}", currentPos, this.fileSize);
        return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
    }

    public long getFileFromOffset() {
        return this.fileFromOffset;
    }

    /**
     * 只将数据写到fileChannel
     * @param data
     * @return
     */
    public boolean appendMessage(final byte[] data) {
        int currentPos = this.wrotePosition.get();

        if ((currentPos + data.length) <= this.fileSize) {
            try {
                this.fileChannel.position(currentPos);
                this.fileChannel.write(ByteBuffer.wrap(data));
            } catch (Throwable e) {
                log.error("Error occurred when append message to mappedFile.", e);
            }
            this.wrotePosition.addAndGet(data.length);
            return true;
        }

        return false;
    }

    /**
     * Content of data from offset to offset + length will be wrote to file.
     *
     * @param offset The offset of the subarray to be used.
     * @param length The length of the subarray to be used.
     */
    public boolean appendMessage(final byte[] data, final int offset, final int length) {
        int currentPos = this.wrotePosition.get();

        if ((currentPos + length) <= this.fileSize) {
            try {
                this.fileChannel.position(currentPos);
                this.fileChannel.write(ByteBuffer.wrap(data, offset, length));
            } catch (Throwable e) {
                log.error("Error occurred when append message to mappedFile.", e);
            }
            this.wrotePosition.addAndGet(length);
            return true;
        }

        return false;
    }

    /**
     * @return The current flushed position
     *
     * 刷盘，从fileChannel or mappedByteBuffer
     */
    public int flush(final int flushLeastPages) {
        if (this.isAbleToFlush(flushLeastPages)) {
            if (this.hold()) {
                int value = getReadPosition();

                try {
                    //We only append data to fileChannel or mappedByteBuffer, never both.
                    if (writeBuffer != null || this.fileChannel.position() != 0) {
                        // 将之前已commit到fileChannel的数据刷到磁盘
                        // Forces any updates to this channel's file to be written to the storage
                        //     * device that contains it.
                        this.fileChannel.force(false);
                    } else {
                        // Forces any changes made to this buffer's content to be written to the
                        //     * storage device containing the mapped file.
                        this.mappedByteBuffer.force();
                    }
                } catch (Throwable e) {
                    log.error("Error occurred when force data to disk.", e);
                }

                this.flushedPosition.set(value);
                this.release();
            } else {
                log.warn("in flush, hold failed, flush offset = " + this.flushedPosition.get());
                this.flushedPosition.set(getReadPosition());
            }
        }
        return this.getFlushedPosition();
    }

    /**
     * 提交数据 从writeBuffer到mappedByteBuffer--其实是写到fileChannel并不是mappedByteBuffer，如果没有用到内存池则不需要提交数据
     * @param commitLeastPages
     * @return
     */
    public int commit(final int commitLeastPages) {
        if (writeBuffer == null) {
            //no need to commit data to file channel, so just regard wrotePosition as committedPosition.
            return this.wrotePosition.get();
        }
        if (this.isAbleToCommit(commitLeastPages)) {
            if (this.hold()) {
                //
                commit0(commitLeastPages);
                this.release();
            } else {
                log.warn("in commit, hold failed, commit offset = " + this.committedPosition.get());
            }
        }

        // All dirty data has been committed to FileChannel. dirty data表示写入writeBuffer但还未提交的数据
        if (writeBuffer != null && this.transientStorePool != null && this.fileSize == this.committedPosition.get()) {
            this.transientStorePool.returnBuffer(writeBuffer);
            this.writeBuffer = null;
        }

        return this.committedPosition.get();
    }

    /**
     * 将数据写到channel，类似于netty中的flush--writeBuffer类似outboundBuffer,fileChannel类似于tcp缓冲区，this.fileChannel.force()才是将数据刷到文件
     * @param commitLeastPages：本次提交最小页数
     */
    protected void commit0(final int commitLeastPages) {
        int writePos = this.wrotePosition.get();
        int lastCommittedPosition = this.committedPosition.get();

        if (writePos - lastCommittedPosition > commitLeastPages) {
            try {
                // writeBuffer中lastCommittedPosition~writePos的数据写出到channel

                // writeBuffer应该是之前flip()的，才能下一步读出数据写到channel
                ByteBuffer byteBuffer = writeBuffer.slice();
                byteBuffer.position(lastCommittedPosition);
                byteBuffer.limit(writePos);
                this.fileChannel.position(lastCommittedPosition);

                // byteBuffer数据写到fileChannel
                this.fileChannel.write(byteBuffer);
                this.committedPosition.set(writePos);
            } catch (Throwable e) {
                log.error("Error occurred when commit data to FileChannel.", e);
            }
        }
    }

    /**
     * 判断mappedByteBuffer或fileChannel是否有可flush的数据
     * @param flushLeastPages
     * @return
     */
    private boolean isAbleToFlush(final int flushLeastPages) {
        int flush = this.flushedPosition.get();
        int write = getReadPosition();

        if (this.isFull()) {
            return true;
        }

        if (flushLeastPages > 0) {
            return ((write / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE)) >= flushLeastPages;
        }

        return write > flush;
    }

    protected boolean isAbleToCommit(final int commitLeastPages) {
        int flush = this.committedPosition.get();
        int write = this.wrotePosition.get();

        if (this.isFull()) {
            return true;
        }

        if (commitLeastPages > 0) {
            return ((write / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE)) >= commitLeastPages;
        }

        return write > flush;
    }

    public int getFlushedPosition() {
        return flushedPosition.get();
    }

    public void setFlushedPosition(int pos) {
        this.flushedPosition.set(pos);
    }

    public boolean isFull() {
        return this.fileSize == this.wrotePosition.get();
    }

    /**
     *
     * @param pos:消息在文件内的偏移量
     * @param size
     * @return
     */
    public SelectMappedBufferResult selectMappedBuffer(int pos, int size) {
        // MappedFile中最大可读位置
        int readPosition = getReadPosition();
        if ((pos + size) <= readPosition) {
            if (this.hold()) {
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                ByteBuffer byteBufferNew = byteBuffer.slice();
                // pos-lim之间的数据就是长度为size的消息 myConfusion:但如果mappedByteBuffer没数据呢（transientStorePoolEnable==true时数据是从writeBuffer到channel的）
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            } else {
                log.warn("matched, but hold failed, request pos: " + pos + ", fileFromOffset: "
                    + this.fileFromOffset);
            }
        } else {
            log.warn("selectMappedBuffer request pos invalid, request pos: " + pos + ", size: " + size
                + ", fileFromOffset: " + this.fileFromOffset);
        }

        return null;
    }

    /**
     * 查找pos到最大可读之间的数据（一个文件中的）
     * @param pos：文件中的offset
     * @return
     */
    public SelectMappedBufferResult selectMappedBuffer(int pos) {
        int readPosition = getReadPosition();
        if (pos < readPosition && pos >= 0) {
            if (this.hold()) {
                ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
                byteBuffer.position(pos);
                int size = readPosition - pos;
                // byteBuffer的pos~lim数据由byteBufferNew指向
                ByteBuffer byteBufferNew = byteBuffer.slice();
                byteBufferNew.limit(size);
                return new SelectMappedBufferResult(this.fileFromOffset + pos, byteBufferNew, size, this);
            }
        }

        return null;
    }

    @Override
    public boolean cleanup(final long currentRef) {
        if (this.isAvailable()) {
            log.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have not shutdown, stop unmapping.");
            return false;
        }

        if (this.isCleanupOver()) {
            log.error("this file[REF:" + currentRef + "] " + this.fileName
                + " have cleanup, do not do it again.");
            return true;
        }

        // 释放mappedByteBuffer--writeBuffer不需要被释放吗？--不需要，因为是一个可以循环使用的内存池
        clean(this.mappedByteBuffer);

        // 少了一个文件
        TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(this.fileSize * (-1));
        TOTAL_MAPPED_FILES.decrementAndGet();

        log.info("unmap file[REF:" + currentRef + "] " + this.fileName + " OK");
        return true;
    }

    /**
     * 销毁MappedFile 释放mappedByteBuffer、fileChannel关闭、
     * writeBuffer在此时应该全部提交到fileChannel了，所以早就回收到transientStorePool中了
     * @param intervalForcibly
     * @return
     */
    public boolean destroy(final long intervalForcibly) {
        // 1. 释放mappedByteBuffer
        this.shutdown(intervalForcibly);

        if (this.isCleanupOver()) {
            // 已释放mappedByteBuffer
            try {
                // 关闭文件通道
                this.fileChannel.close();
                log.info("close file channel " + this.fileName + " OK");

                long beginTime = System.currentTimeMillis();
                // 磁盘删除物理文件
                boolean result = this.file.delete();
                log.info("delete file[REF:" + this.getRefCount() + "] " + this.fileName
                    + (result ? " OK, " : " Failed, ") + "W:" + this.getWrotePosition() + " M:"
                    + this.getFlushedPosition() + ", "
                    + UtilAll.computeElapsedTimeMilliseconds(beginTime));
            } catch (Exception e) {
                log.warn("close file channel " + this.fileName + " Failed. ", e);
            }

            return true;
        } else {
            log.warn("destroy mapped file[REF:" + this.getRefCount() + "] " + this.fileName
                + " Failed. cleanupOver: " + this.cleanupOver);
        }

        return false;
    }

    public int getWrotePosition() {
        return wrotePosition.get();
    }

    public void setWrotePosition(int pos) {
        this.wrotePosition.set(pos);
    }

    /**
     * @return The max position which have valid data
     */
    public int getReadPosition() {
        return this.writeBuffer == null ? this.wrotePosition.get() : this.committedPosition.get();
    }

    public void setCommittedPosition(int pos) {
        this.committedPosition.set(pos);
    }

    public void warmMappedFile(FlushDiskType type, int pages) {
        long beginTime = System.currentTimeMillis();
        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        int flush = 0;
        long time = System.currentTimeMillis();
        for (int i = 0, j = 0; i < this.fileSize; i += MappedFile.OS_PAGE_SIZE, j++) {
            byteBuffer.put(i, (byte) 0);
            // force flush when flush disk type is sync
            if (type == FlushDiskType.SYNC_FLUSH) {
                if ((i / OS_PAGE_SIZE) - (flush / OS_PAGE_SIZE) >= pages) {
                    flush = i;
                    mappedByteBuffer.force();
                }
            }

            // prevent gc
            if (j % 1000 == 0) {
                log.info("j={}, costTime={}", j, System.currentTimeMillis() - time);
                time = System.currentTimeMillis();
                try {
                    Thread.sleep(0);
                } catch (InterruptedException e) {
                    log.error("Interrupted", e);
                }
            }
        }

        // force flush when prepare load finished
        if (type == FlushDiskType.SYNC_FLUSH) {
            log.info("mapped file warm-up done, force to disk, mappedFile={}, costTime={}",
                this.getFileName(), System.currentTimeMillis() - beginTime);
            mappedByteBuffer.force();
        }
        log.info("mapped file warm-up done. mappedFile={}, costTime={}", this.getFileName(),
            System.currentTimeMillis() - beginTime);

        this.mlock();
    }

    public String getFileName() {
        return fileName;
    }

    public MappedByteBuffer getMappedByteBuffer() {
        return mappedByteBuffer;
    }

    public ByteBuffer sliceByteBuffer() {
        return this.mappedByteBuffer.slice();
    }

    public long getStoreTimestamp() {
        return storeTimestamp;
    }

    public boolean isFirstCreateInQueue() {
        return firstCreateInQueue;
    }

    public void setFirstCreateInQueue(boolean firstCreateInQueue) {
        this.firstCreateInQueue = firstCreateInQueue;
    }

    public void mlock() {
        final long beginTime = System.currentTimeMillis();
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address();
        Pointer pointer = new Pointer(address);
        {
            int ret = LibC.INSTANCE.mlock(pointer, new NativeLong(this.fileSize));
            log.info("mlock {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
        }

        {
            int ret = LibC.INSTANCE.madvise(pointer, new NativeLong(this.fileSize), LibC.MADV_WILLNEED);
            log.info("madvise {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
        }
    }

    public void munlock() {
        final long beginTime = System.currentTimeMillis();
        final long address = ((DirectBuffer) (this.mappedByteBuffer)).address();
        Pointer pointer = new Pointer(address);
        int ret = LibC.INSTANCE.munlock(pointer, new NativeLong(this.fileSize));
        log.info("munlock {} {} {} ret = {} time consuming = {}", address, this.fileName, this.fileSize, ret, System.currentTimeMillis() - beginTime);
    }

    //testable
    File getFile() {
        return this.file;
    }

    @Override
    public String toString() {
        return this.fileName;
    }
}
