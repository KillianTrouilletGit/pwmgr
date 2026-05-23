package com.pwmgr.android.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.Parcel
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import com.pwmgr.android.R

/**
 * System-bound autofill provider.
 *
 * The flow is intentionally minimal: every fill request returns a single authentication
 * suggestion ("Unlock PwMgr to autofill"). Tapping that fires a PendingIntent → opens
 * [AutofillUnlockActivity], which authenticates the user (biometric or master password),
 * decrypts the vault, matches entries against the parsed form, and returns the actual
 * [FillResponse] to the system via setResult.
 *
 * Why not return Datasets directly on the first request? Because that would require holding
 * a decrypted vault in this service's process — fragile across cold starts and a security
 * smell. Routing through an Activity gives us a clean authentication boundary.
 */
class PwMgrAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val context = request.fillContexts.lastOrNull() ?: run {
            callback.onSuccess(null)
            return
        }
        val structure = context.structure
        val callingPackage = structure.activityComponent?.packageName ?: packageName
        val parsed = FormParser.parse(structure, callingPackage)
        if (parsed == null) {
            // No login form detected on this screen.
            callback.onSuccess(null)
            return
        }

        val intent = Intent(this, AutofillUnlockActivity::class.java).apply {
            putExtra(AutofillUnlockActivity.EXTRA_PACKAGE_NAME, parsed.packageName)
            parsed.webDomain?.let { putExtra(AutofillUnlockActivity.EXTRA_WEB_DOMAIN, it) }
            putExtra(AutofillUnlockActivity.EXTRA_PARSED_FORM, parsed.toBytes())
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val presentation = RemoteViews(packageName, R.layout.autofill_unlock_item)
        val ids: Array<AutofillId> = listOfNotNull(parsed.usernameFieldId, parsed.passwordFieldId).toTypedArray()

        val response = FillResponse.Builder()
            .setAuthentication(ids, pendingIntent.intentSender, presentation)
            .build()
        callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // v1: we don't capture save-on-submit. The user adds entries manually from the app.
        // Returning success silently is the documented "do nothing" path.
        callback.onSuccess()
    }

    companion object {
        private const val REQUEST_CODE = 0x12abf111

        /** Pack/unpack a [ParsedForm] across the PendingIntent boundary. */
        fun ParsedForm.toBytes(): ByteArray {
            val p = Parcel.obtain()
            try {
                p.writeParcelable(usernameFieldId, 0)
                p.writeParcelable(passwordFieldId, 0)
                return p.marshall()
            } finally {
                p.recycle()
            }
        }

        @Suppress("DEPRECATION")
        fun parsedFormFromBytes(bytes: ByteArray): Pair<AutofillId?, AutofillId?> {
            val p = Parcel.obtain()
            try {
                p.unmarshall(bytes, 0, bytes.size)
                p.setDataPosition(0)
                val username = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    p.readParcelable(AutofillId::class.java.classLoader, AutofillId::class.java)
                } else {
                    p.readParcelable<AutofillId>(AutofillId::class.java.classLoader)
                }
                val password = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    p.readParcelable(AutofillId::class.java.classLoader, AutofillId::class.java)
                } else {
                    p.readParcelable<AutofillId>(AutofillId::class.java.classLoader)
                }
                return username to password
            } finally {
                p.recycle()
            }
        }
    }
}
