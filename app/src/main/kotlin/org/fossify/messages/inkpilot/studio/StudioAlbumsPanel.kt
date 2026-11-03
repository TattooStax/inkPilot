package org.fossify.messages.inkpilot.studio

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

internal class StudioAlbumsPanel(private val host: StudioPanelHost, private val store: StudioAlbumStore) {
    private val files = StudioImageFiles(host.activity.applicationContext, store)
    private var content = host.column()
    private var selected: StudioAlbum? = null
    private var showingImages = false
    private var renderVersion = 0

    fun view(): View {
        content = host.column()
        render()
        return host.body(content)
    }

    private fun render() {
        content.removeAllViews()
        renderVersion++
        if (showingImages) renderImages() else renderAlbums(renderVersion)
    }

    private fun renderAlbums(version: Int) {
        content.addView(host.text("Albums", 22f))
        content.addView(host.button("Create album") { editAlbum(null) })
        content.addView(host.button("All images") { selected = null; showingImages = true; render() })
        host.load({ store.albums() }) { albums ->
            if (renderVersion != version) return@load
            albums.forEach { album ->
                val label = album.name + if (album.artist.isBlank()) "" else " · ${album.artist}"
                content.addView(host.button(label) { selected = album; showingImages = true; render() })
            }
        }
    }

    private fun renderImages() {
        val album = selected
        content.addView(host.button("‹ Albums") { showingImages = false; render() })
        content.addView(host.text(album?.name ?: "All images", 22f))
        if (album != null) content.addView(host.button("Edit album") { editAlbum(album) })
        content.addView(host.button("Import image") {
            host.activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, StudioPanels.IMPORT_IMAGE)
        })
        val listing = host.column()
        content.addView(listing)
        loadImages(listing, album, 0)
    }

    private fun loadImages(listing: LinearLayout, album: StudioAlbum?, offset: Int) {
        host.load({ store.images(album?.id, offset) }) { images ->
            if (images.isEmpty() && offset == 0) listing.addView(host.text("No images yet."))
            images.forEach { image ->
                val row = LinearLayout(host.activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, host.dp(8), 0, host.dp(8))
                    setOnClickListener { preview(image, album) }
                    isFocusable = true
                    contentDescription = "Open ${image.displayName}"
                }
                val thumbnail = ImageView(host.activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = image.displayName
                }
                row.addView(thumbnail, LinearLayout.LayoutParams(host.dp(96), host.dp(96)))
                row.addView(host.text(image.displayName).apply { setPadding(host.dp(12), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                listing.addView(row)
                host.load({ files.thumbnail(image, 160) }) { thumbnail.setImageBitmap(it) }
            }
            if (images.size == 100) {
                val more = host.button("Load more images") {}
                more.setOnClickListener {
                    listing.removeView(more); loadImages(listing, album, offset + images.size)
                }
                listing.addView(more)
            }
        }
    }

    fun acceptImage(uri: Uri) {
        chooseAlbum("Add image to album") { album ->
            host.load({ files.import(uri, album.id) }) {
                selected = album; showingImages = true; render()
            }
        }
    }

    private fun chooseAlbum(title: String, action: (StudioAlbum) -> Unit) {
        host.load({ store.albums() }) { albums ->
            AlertDialog.Builder(host.activity).setTitle(title)
                .setItems(albums.map { it.name }.toTypedArray()) { _, index -> action(albums[index]) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun editAlbum(album: StudioAlbum?) {
        val fields = host.column().apply { setPadding(host.dp(20), 0, host.dp(20), 0) }
        val name = host.field("Album name", album?.name.orEmpty())
        val artist = host.field("Artist (optional)", album?.artist.orEmpty())
        fields.addView(name); fields.addView(artist)
        val dialog = AlertDialog.Builder(host.activity).setTitle(if (album == null) "New album" else "Edit album")
            .setView(fields).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = name.text.toString(); val newArtist = artist.text.toString()
                host.load({ store.saveAlbum(album?.id, newName, newArtist) }) { id ->
                    if (album != null) selected = StudioAlbum(id, newName.trim(), newArtist.trim())
                    dialog.dismiss(); render()
                }
            }
        }
        dialog.show()
    }

    private fun preview(image: StudioImage, album: StudioAlbum?) {
        host.load({ files.thumbnail(image, 1_000) }) { bitmap ->
            val preview = ImageView(host.activity).apply {
                setImageBitmap(bitmap); adjustViewBounds = true
                contentDescription = image.displayName
                maxHeight = host.dp(420)
            }
            val dialog = AlertDialog.Builder(host.activity).setTitle(image.displayName)
                .setView(preview).setNegativeButton("Close", null)
                .setPositiveButton("Add to album") { _, _ ->
                    chooseAlbum("Add to album") { destination ->
                        host.load({ store.addMembership(destination.id, image.id) }) { render() }
                    }
                }
            if (album != null) dialog.setNeutralButton("Remove from album") { _, _ ->
                host.load({ store.removeMembership(album.id, image.id) }) { render() }
            }
            dialog.show()
        }
    }
}
