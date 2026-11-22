package ai.sakana.aura.gallery.di

import android.content.Context
import ai.sakana.aura.gallery.data.deduplication.AndroidGrayThumbnailSource
import ai.sakana.aura.gallery.data.deduplication.ContentResolverByteSource
import ai.sakana.aura.gallery.data.deduplication.ExactDuplicateDetector
import ai.sakana.aura.gallery.data.deduplication.HashStore
import ai.sakana.aura.gallery.data.deduplication.MediaByteSource
import ai.sakana.aura.gallery.data.deduplication.PerceptualHashGenerator
import ai.sakana.aura.gallery.data.deduplication.SimilarPhotoDetector
import ai.sakana.aura.gallery.data.repository.RoomHashStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DeduplicationBindings {
    @Binds
    @Singleton
    abstract fun bindHashStore(implementation: RoomHashStore): HashStore
}

@Module
@InstallIn(SingletonComponent::class)
object DeduplicationModule {
    @Provides
    @Singleton
    fun provideMediaByteSource(@ApplicationContext context: Context): MediaByteSource =
        ContentResolverByteSource(context.contentResolver)

    @Provides
    @Singleton
    fun provideExactDuplicateDetector(source: MediaByteSource): ExactDuplicateDetector =
        ExactDuplicateDetector(source)

    @Provides
    @Singleton
    fun provideSimilarPhotoDetector(@ApplicationContext context: Context): SimilarPhotoDetector =
        SimilarPhotoDetector(PerceptualHashGenerator(AndroidGrayThumbnailSource(context)))
}
