package com.iheii.veloradrive.video

import com.iheii.veloradrive.link.VideoItem

/**
 * Lista de la que se abrió el reproductor (pestaña, lista o búsqueda), para anterior/siguiente.
 * Vive con el proceso: no hace falta pasar listas largas por el Intent.
 */
object VideoQueue {
    var items: List<VideoItem> = emptyList()
        private set

    fun set(list: List<VideoItem>) {
        items = list.filter { it.playlistId == null }
    }

    fun indexOf(id: String) = items.indexOfFirst { it.id == id }

    /** Se abre algo que no está en la lista (p. ej. desde el celular): la lista queda solo con eso. */
    fun ensure(item: VideoItem) {
        if (indexOf(item.id) < 0) items = listOf(item)
    }

    fun get(id: String) = items.firstOrNull { it.id == id }
    fun previous(id: String) = indexOf(id).let { if (it > 0) items[it - 1] else null }
    fun next(id: String) = indexOf(id).let { if (it >= 0 && it < items.size - 1) items[it + 1] else null }
}
