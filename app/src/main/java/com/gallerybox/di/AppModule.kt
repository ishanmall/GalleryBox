package com.gallerybox.di

// --- IMPORTS ---
// This is our toolbox. We are bringing in the blueprints for our database, our video/audio player,
// and the tools (Dagger/Hilt) that help us build and distribute them.
import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import com.gallerybox.data.AlbumThumbnailDao
import com.gallerybox.data.DocumentDao
import com.gallerybox.data.GalleryDao
import com.gallerybox.data.GalleryDatabase
import com.gallerybox.data.MusicDao
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * --- THE TOOL FACTORY (AppModule) ---
 * "Dependency Injection" (DI) is a fancy programming term for a Tool Factory.
 * Instead of every single screen in our app trying to build its own database connection or its own
 * music player from scratch, this Factory builds exactly ONE perfect version of each tool.
 * When a screen needs a tool, it just asks the Factory: "Hey, can I borrow the Database?"
 *
 * `@Module` tells the Android system: "This is a factory that makes tools."
 * `@InstallIn(SingletonComponent::class)` means: "These tools should live for the entire time the app is open."
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * THE MASTER RECORD PLAYER.
     * Builds our ExoPlayer (Google's powerful media engine).
     * `@Provides` tells the factory: "Here is how you build this tool."
     * `@Singleton` means: "Only ever build ONE of these. If someone else asks for it, give them the exact same one."
     *
     * We configure it here to automatically pause if the headphones are unplugged (`setHandleAudioBecomingNoisy(true)`).
     */
    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer =
        ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build()

    /**
     * THE TRANSLATOR (Gson).
     * Gson is a tool that translates complex app data (like a list of 50 photos with dates and sizes)
     * into a simple, plain text format called JSON. This makes it easy to save to the phone's hard drive.
     */
    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    /**
     * THE MASTER FILING CABINET (Room Database).
     * This builds the actual physical database on the phone where we save our Trash Bin, Favorites, and Memories.
     */
    @Provides
    @Singleton
    fun provideGalleryDatabase(@ApplicationContext context: Context): GalleryDatabase =
        // "Room" is Android's database builder. We tell it to build a cabinet named "GalleryDatabase".
        Room.databaseBuilder(context, GalleryDatabase::class.java, GalleryDatabase.DATABASE_NAME)
            // "fallbackToDestructiveMigration" is the Bulldozer rule.
            // If we release an app update that changes the shape of the database (like adding a new column for "Video Length"),
            // and we forget to give it instructions on how to upgrade the old database, it will just bulldoze the old one
            // and build a brand new empty one so the app doesn't crash.
            .fallbackToDestructiveMigration()
            .build()

    // --- THE FILE CLERKS (DAOs) ---
    // A Database is just a giant metal box. You need File Clerks (Data Access Objects or DAOs) to actually open
    // the drawers, put files in, and read them out. We have different clerks for different jobs.

    /**
     * THE GALLERY CLERK.
     * Handles everything related to saving and loading the photo grid, the trash bin, and the hidden vault.
     */
    @Provides
    @Singleton
    fun provideGalleryDao(database: GalleryDatabase): GalleryDao = database.galleryDao()

    /**
     * THE MUSIC CLERK.
     * Handles remembering which songs the user has favorited and tracking how many times they played a specific track.
     */
    @Provides
    @Singleton
    fun provideMusicDao(database: GalleryDatabase): MusicDao = database.musicDao()

    /**
     * THE ALBUM COVER CLERK.
     * Specifically handles saving and loading the 4-picture preview thumbnails for the album folders.
     */
    @Provides
    @Singleton
    fun provideAlbumThumbnailDao(database: GalleryDatabase): AlbumThumbnailDao = database.albumThumbnailDao()

}