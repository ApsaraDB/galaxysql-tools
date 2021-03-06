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

package model.encrypt;

import model.config.EncryptionMode;
import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;

public class CipherUtil {


    public static BlockCipher getAesCipher(EncryptionMode encryptionMode) {
        switch (encryptionMode) {
        case AES_CBC:
            return new CBCBlockCipher(new AESEngine());
        default:
            throw new IllegalArgumentException("Unsupported encryption mode: " + encryptionMode);
        }
    }
}
