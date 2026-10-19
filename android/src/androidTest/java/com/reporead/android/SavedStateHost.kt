package com.reporead.android

import android.os.Bundle
import android.os.Parcel
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Saves the real activity boundary and carries it through a Parcel. Closing the scenario and supplying this to a new
 * TestHostActivity restores saved state into fresh owners, rather than retaining a configuration-change ViewModelStore.
 */
internal fun saveActivityState(scenario: ActivityScenario<TestHostActivity>): Bundle {
    val saved = Bundle()
    scenario.onActivity {
        InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, saved)
    }
    val parcel = Parcel.obtain()
    try {
        parcel.writeBundle(saved)
        parcel.setDataPosition(0)
        return checkNotNull(parcel.readBundle(TestHostActivity::class.java.classLoader))
    } finally {
        parcel.recycle()
    }
}
