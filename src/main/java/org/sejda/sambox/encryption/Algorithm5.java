/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sejda.sambox.encryption;

import static org.sejda.sambox.encryption.EncryptUtils.ENCRYPT_PADDING;

import java.security.MessageDigest;

import org.bouncycastle.util.Arrays;

/**
 * Algorithm 5 as defined in Chap 7.6.3.4 of PDF 32000-1:2008
 * 
 * @author Andrea Vacondio
 *
 */
class Algorithm5 implements PasswordAlgorithm
{
    private Algorithm2 algo = new Algorithm2();
    private MessageDigest digest = MessageDigests.md5();
    private ARC4Engine engine = new ARC4Engine();

    @Override
    public byte[] computePassword(StandardSecurity security)
    {
        security.encryption.revision.requireAtLeast(StandardSecurityHandlerRevision.R3,
                "Algorithm 5 requires a security handler of revision 3 or greater");
        byte[] arc4Key = algo.computeEncryptionKey(security);
        digest.update(ENCRYPT_PADDING);
        digest.update(security.documentId());
        byte[] encrypted = engine.encryptBytes(Arrays.copyOf(digest.digest(), 16), arc4Key);
        byte[] iterationKey = new byte[arc4Key.length];
        for (int i = 1; i < 20; i++)
        {
            iterationKey = Arrays.copyOf(arc4Key, arc4Key.length);
            for (int j = 0; j < iterationKey.length; j++)
            {
                iterationKey[j] = (byte) (iterationKey[j] ^ (byte) i);
            }
            encrypted = engine.encryptBytes(encrypted, iterationKey);
        }
        return Arrays.concatenate(Arrays.copyOf(encrypted, 16), Arrays.copyOf(ENCRYPT_PADDING, 16));
    }
}
