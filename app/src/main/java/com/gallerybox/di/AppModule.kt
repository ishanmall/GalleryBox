package com.gallerybox.di

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

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer =
        ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build()

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideGalleryDatabase(@ApplicationContext context: Context): GalleryDatabase =
        Room.databaseBuilder(context, GalleryDatabase::class.java, GalleryDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideGalleryDao(database: GalleryDatabase): GalleryDao = database.galleryDao()

    @Provides
    @Singleton
    fun provideMusicDao(database: GalleryDatabase): MusicDao = database.musicDao()

    @Provides
    @Singleton
    fun provideDocumentDao(database: GalleryDatabase): DocumentDao = database.documentDao()

    @Provides
    @Singleton
    fun provideAlbumThumbnailDao(database: GalleryDatabase): AlbumThumbnailDao = database.albumThumbnailDao()

}