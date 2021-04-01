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
package org.apache.rocketmq.client.impl.consumer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.rocketmq.common.message.MessageQueue;

/**
 * Message lock,strictly ensure the single queue only one thread at a time consuming
 */
public class MessageQueueLock {
    // 线程安全的
    private ConcurrentMap<MessageQueue, Object> mqLockTable =
        new ConcurrentHashMap<MessageQueue, Object>();

    public Object fetchLockObject(final MessageQueue mq) {
        Object objLock = this.mqLockTable.get(mq);
        if (null == objLock) {
            objLock = new Object();

            // 当有两个线程A与B同时到达此处时，A返回null,B返回A put的；但如果C才执行到第一行，那么C返回的是B.objLock
            // --！！！putIfAbsent是指当没有数据时才插入成功，所以B put不成功,C返回的仍然是A.objLock。对同一个对象加锁，保证同步
            Object prevLock = this.mqLockTable.putIfAbsent(mq, objLock);
            if (prevLock != null) {
                objLock = prevLock;
            }
        }

        // A 返回A的objLock,B也返回A的objLock
        return objLock;
    }
}
