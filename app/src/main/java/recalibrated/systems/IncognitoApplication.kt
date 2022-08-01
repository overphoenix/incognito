package recalibrated.systems

import com.celzero.bravedns.AppModules
import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class IncognitoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        //turnOnStrictMode()

        startKoin {
            if (BuildConfig.DEBUG) androidLogger()
            androidContext(this@IncognitoApplication)
            koin.loadModules(AppModules)
        }

        if (DEBUG) Log.d(LOG_TAG_SCHEDULER, "Schedule job")
        get<WorkScheduler>().scheduleAppExitInfoCollectionJob()
        get<WorkScheduler>().scheduleDatabaseRefreshJob()
    }

    private fun turnOnStrictMode() {
        if (!BuildConfig.DEBUG) return
        // Uncomment the code below to enable the StrictModes.
        // To test the apps disk read/writes, network usages.
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().permitDiskReads().permitDiskWrites().permitNetwork().build())
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectAll().detectLeakedSqlLiteObjects().penaltyLog().build())
    }
}
