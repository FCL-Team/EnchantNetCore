package org.fcl.enchantnetcore.state;

import androidx.annotation.Nullable;

public class EnchantNetSnapshot {
    public final EnchantNetState state;
    public final EnchantNetRole role;
    @Nullable
    public final EnchantNetException exception;
    @Nullable
    public final String message;       // optional detail
    @Nullable
    public final String inviteCode;   // host-side generated
    @Nullable
    public final String backupServer; // guest-side provided on success
    public final long timestamp;

    public EnchantNetSnapshot(EnchantNetState s, EnchantNetRole r, @Nullable EnchantNetException e, @Nullable String msg, @Nullable String invite, @Nullable String backup, long ts) {
        this.state = s;
        this.role = r;
        this.exception = e;
        this.message = msg;
        this.inviteCode = invite;
        this.backupServer = backup;
        this.timestamp = ts;
    }
}