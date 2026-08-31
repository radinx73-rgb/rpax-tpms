package com.rpax.tpms

import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for the Android Auto ("Car" profile) experience.
 * Provides a read-only glance at current TPMS readings while driving.
 */
class RpaxCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: android.content.Intent): Screen {
                return RpaxScreen(carContext)
            }
        }
    }
}
