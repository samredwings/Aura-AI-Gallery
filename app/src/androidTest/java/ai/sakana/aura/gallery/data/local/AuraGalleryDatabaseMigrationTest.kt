package ai.sakana.aura.gallery.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuraGalleryDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AuraGalleryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migration1To10_preservesMediaAcrossFullHistoricalChain() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            insertMedia("content://aura/migration-v1")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            10,
            true,
            AuraGalleryDatabase.MIGRATION_1_2,
            AuraGalleryDatabase.MIGRATION_2_3,
            AuraGalleryDatabase.MIGRATION_3_4,
            AuraGalleryDatabase.MIGRATION_4_5,
            AuraGalleryDatabase.MIGRATION_5_6,
            AuraGalleryDatabase.MIGRATION_6_7,
            AuraGalleryDatabase.MIGRATION_7_8,
            AuraGalleryDatabase.MIGRATION_8_9,
            AuraGalleryDatabase.MIGRATION_9_10,
        )
        try {
            assertMediaPreserved(migrated, "content://aura/migration-v1")
            listOf(
                "scene_embeddings",
                "detected_faces",
                "ai_sets",
                "ai_set_memberships",
                "face_embeddings",
                "person_clusters",
                "edit_history",
                "recognition_progress",
                "media_versions",
                "agent_skills",
            ).forEach { tableName -> assertTableExists(migrated, tableName) }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migration6To7_preservesMediaAndMatchesExportedV7Schema() {
        helper.createDatabase(TEST_DATABASE, 6).apply {
            insertMedia("content://aura/migration-v6-v7")
            close()
        }

        // MigrationTestHelper validates the resulting schema against the checked-in 7.json,
        // including columns, defaults, indexes, primary key, and foreign-key constraints.
        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            7,
            true,
            AuraGalleryDatabase.MIGRATION_6_7,
        )
        try {
            assertMediaPreserved(migrated, "content://aura/migration-v6-v7")
            assertTableExists(migrated, "face_embeddings")
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migration6To10_preservesMediaAndAppliesEveryRecordedMigration() {
        helper.createDatabase(TEST_DATABASE, 6).apply {
            insertMedia("content://aura/migration-v6")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            10,
            true,
            AuraGalleryDatabase.MIGRATION_6_7,
            AuraGalleryDatabase.MIGRATION_7_8,
            AuraGalleryDatabase.MIGRATION_8_9,
            AuraGalleryDatabase.MIGRATION_9_10,
        )
        try {
            assertMediaPreserved(migrated, "content://aura/migration-v6")
            assertTableExists(migrated, "face_embeddings")
            assertTableExists(migrated, "person_clusters")
            assertTableExists(migrated, "edit_history")
            assertTableExists(migrated, "recognition_progress")
            assertTableExists(migrated, "media_versions")
            assertTableExists(migrated, "agent_skills")
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migration8To10_preservesMediaAndCreatesNewTables() {
        helper.createDatabase(TEST_DATABASE, 8).apply {
            insertMedia("content://aura/migration-v8")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            10,
            true,
            AuraGalleryDatabase.MIGRATION_8_9,
            AuraGalleryDatabase.MIGRATION_9_10,
        )
        try {
            assertMediaPreserved(migrated, "content://aura/migration-v8")
            assertTableExists(migrated, "recognition_progress")
            assertTableExists(migrated, "media_versions")
            assertTableExists(migrated, "agent_skills")
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migration9To10_preservesMediaAndCreatesVersionAndSkillTables() {
        helper.createDatabase(TEST_DATABASE, 9).apply {
            insertMedia("content://aura/migration-v9")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            10,
            true,
            AuraGalleryDatabase.MIGRATION_9_10,
        )
        try {
            assertMediaPreserved(migrated, "content://aura/migration-v9")
            assertTableExists(migrated, "media_versions")
            assertTableExists(migrated, "agent_skills")
        } finally {
            migrated.close()
        }
    }

    private fun SupportSQLiteDatabase.insertMedia(contentUri: String) {
        execSQL(
            """
            INSERT INTO media_items (
                volumeName, contentUri, sizeBytes, dateTakenEpochMillis,
                width, height, isFavorite, sourceType, isAvailable
            ) VALUES (
                'external_primary', '$contentUri', 1234, 1700000000000,
                640, 480, 1, 'MEDIA_STORE', 1
            )
            """.trimIndent(),
        )
    }

    private fun assertMediaPreserved(database: SupportSQLiteDatabase, contentUri: String) {
        database.query(
            "SELECT contentUri, sizeBytes, isFavorite FROM media_items WHERE contentUri = ?",
            arrayOf(contentUri),
        ).use { cursor ->
            assertTrue("Expected the original media row to survive migration", cursor.moveToFirst())
            assertEquals(contentUri, cursor.getString(0))
            assertEquals(1234L, cursor.getLong(1))
            assertEquals(1, cursor.getInt(2))
            assertTrue("Expected exactly one matching media row", !cursor.moveToNext())
        }
    }

    private fun assertTableExists(database: SupportSQLiteDatabase, tableName: String) {
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName),
        ).use { cursor ->
            assertTrue("Expected table $tableName after migration", cursor.moveToFirst())
        }
    }

    private companion object {
        const val TEST_DATABASE = "aura-gallery-room-migration-test"
    }
}
