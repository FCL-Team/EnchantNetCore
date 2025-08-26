package org.fcl.enchantnetcore.state;

import androidx.annotation.NonNull;

public interface EnchantNetStateListener {
    /** Called on every state transition with a fresh snapshot. Main-thread recommended when wiring to UI. */
    void onStateChanged(@NonNull EnchantNetSnapshot snapshot);
}