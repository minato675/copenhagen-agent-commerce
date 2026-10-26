package com.copenhagen.wallet

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** One session and payment-operation owner across the wallet and notification task. */
object WalletSession : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    @MainThread
    fun model(application: Application): AppModel =
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[AppModel::class.java]
}
