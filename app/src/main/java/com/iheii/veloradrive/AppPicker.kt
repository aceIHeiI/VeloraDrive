package com.iheii.veloradrive

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iheii.veloradrive.databinding.ItemPickBinding
import kotlinx.coroutines.launch

/** Diálogo ligero para reasignar un botón; la lista de apps (con iconos en caché) se carga en IO. */
object AppPicker {

    fun pickForSlot(activity: AppCompatActivity, slot: Slot, onChosen: () -> Unit) {
        activity.lifecycleScope.launch {
            val apps = AppRepository.load(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val adapter = PickAdapter(activity, apps)
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.pick_app_for, activity.getString(slot.title)))
                .setAdapter(adapter) { _, which ->
                    SlotStore.assign(activity, slot, adapter.getItem(which)?.component)
                    onChosen()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Primera fila: volver al predeterminado. Luego todas las apps. */
    private class PickAdapter(private val activity: Activity, private val apps: List<AppEntry>) : BaseAdapter() {
        override fun getCount() = apps.size + 1
        override fun getItem(position: Int): AppEntry? = if (position == 0) null else apps[position - 1]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = convertView?.tag as? ItemPickBinding
                ?: ItemPickBinding.inflate(activity.layoutInflater, parent, false).also { it.root.tag = it }
            val app = getItem(position)
            if (app == null) {
                b.icon.setImageDrawable(null)
                b.label.setText(R.string.use_default)
            } else {
                b.icon.setImageDrawable(app.icon)
                b.label.text = app.label
            }
            return b.root
        }
    }
}
