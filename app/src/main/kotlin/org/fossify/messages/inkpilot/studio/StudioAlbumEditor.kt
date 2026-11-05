package org.fossify.messages.inkpilot.studio

import androidx.appcompat.app.AlertDialog

private const val ALBUM_EDITOR_SIDE_PADDING_DP = 20

/** The small album form is shared by the collection and image canvases. */
internal fun openAlbumEditor(
    host: StudioPanelHost,
    store: StudioAlbumStore,
    album: StudioAlbum?,
    onSaved: (StudioAlbum?) -> Unit,
) {
    val fields = host.column().apply {
        setPadding(host.dp(ALBUM_EDITOR_SIDE_PADDING_DP), 0, host.dp(ALBUM_EDITOR_SIDE_PADDING_DP), 0)
    }
    val name = host.field("Album name", album?.name.orEmpty())
    val artist = host.field("Artist (optional)", album?.artist.orEmpty())
    fields.addView(name)
    fields.addView(artist)
    val dialog = AlertDialog.Builder(host.activity).setTitle(if (album == null) "New album" else "Edit album")
        .setView(fields).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = name.text.toString()
            val newArtist = artist.text.toString()
            host.load({ store.saveAlbum(album?.id, newName, newArtist) }) { id ->
                dialog.dismiss()
                onSaved(if (album == null) null else StudioAlbum(id, newName.trim(), newArtist.trim()))
            }
        }
    }
    dialog.show()
}
