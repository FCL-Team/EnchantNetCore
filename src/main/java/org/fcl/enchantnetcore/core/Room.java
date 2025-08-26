package org.fcl.enchantnetcore.core;

import androidx.annotation.NonNull;

public class Room {

        public long roomId;
        public int port;
        public String name;
        public String secret;
        public RoomKind roomKind = RoomKind.INVALID;

        @NonNull
        @Override
        public String toString() {
                return "InviteParseResult{" +
                        "    roomId=" + Long.toUnsignedString(roomId) + ",\n" +
                        "    port=" + port + ",\n" +
                        "    name=\"" + name + "\",\n" +
                        "    secret=\"" + secret + "\",\n" +
                        "    roomKind=" + roomKind + ",\n" +
                        "}";
        }
}