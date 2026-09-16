private val tpmsUpdateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != BleScannerService.ACTION_TPMS_UPDATE) return

        val positionName = intent.getStringExtra(BleScannerService.EXTRA_POSITION) ?: return
        val pressure = intent.getFloatExtra(BleScannerService.EXTRA_PRESSURE, 0f)
        val temp = intent.getIntExtra(BleScannerService.EXTRA_TEMP, 0)
        val batteryOk = intent.getBooleanExtra(BleScannerService.EXTRA_BATTERY_OK, true)

        when (positionName) {
            TpmsDecoder.Position.FRONT.name -> {
                updateFrontUi(pressure, temp, batteryOk)
            }
            TpmsDecoder.Position.REAR.name -> {
                updateRearUi(pressure, temp, batteryOk)
            }
        }
    }
}