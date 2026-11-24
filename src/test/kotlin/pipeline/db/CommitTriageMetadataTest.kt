package pipeline.db

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitTriageMetadataTest {
    @Test fun verifiedUpdateClearsStaleDiscardMetadata(): Unit = runBlocking {
        val dir = Files.createTempDirectory("db-verified-clear").toFile()
        SQLiteDatabaseManager(File(dir, "test.db")).use { db ->
            db.jdbc.createStatement().use { s ->
                s.execute("INSERT INTO repositories(repo_id,name,git_url,default_branch,config_yaml) VALUES('r','r','u','main','')")
                s.execute(
                    """INSERT INTO commits
                       (commit_hash,repo_id,parent_commit,author_date,commit_msg,triage_status,discard_reason,compiler_error_log)
                       VALUES('c','r','p','','m','discarded_build_broken','test_compilation_failure','error: compilation failed')"""
                )
            }

            // Trailing non-null fields exercise NULL bindings followed by value
            // bindings, which is where a placeholder-index shift would surface.
            db.updateCommit(
                CommitTriageUpdate(
                    commitHash = "c",
                    triageStatus = "verified",
                    regressionTestsFound = 1,
                    regressionTestClass = "AppTest",
                )
            )

            db.read { c ->
                c.createStatement().executeQuery(
                    """SELECT triage_status,discard_reason,compiler_error_log,
                              regression_tests_found,regression_test_class
                       FROM commits WHERE commit_hash='c'"""
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("verified", rs.getString("triage_status"))
                    assertNull(rs.getString("discard_reason"))
                    assertNull(rs.getString("compiler_error_log"))
                    // Distinguish SQL NULL from an empty string.
                    assertNull(rs.getObject("discard_reason"))
                    assertNull(rs.getObject("compiler_error_log"))
                    // Values after the NULL bindings must still land in their own columns.
                    assertEquals(1, rs.getInt("regression_tests_found"))
                    assertEquals("AppTest", rs.getString("regression_test_class"))
                }
            }
        }
        dir.deleteRecursively()
    }

    @Test fun discardUpdateStillPersistsReasonAndCompilerLog(): Unit = runBlocking {
        val dir = Files.createTempDirectory("db-discard-keep").toFile()
        SQLiteDatabaseManager(File(dir, "test.db")).use { db ->
            db.jdbc.createStatement().use { s ->
                s.execute("INSERT INTO repositories(repo_id,name,git_url,default_branch,config_yaml) VALUES('r','r','u','main','')")
                s.execute("INSERT INTO commits(commit_hash,repo_id,parent_commit,author_date,commit_msg,triage_status) VALUES('c','r','p','','m','pending')")
            }

            db.updateCommit(
                CommitTriageUpdate(
                    commitHash = "c",
                    triageStatus = "discarded_build_broken",
                    discardReason = "build_broken",
                    compilerErrorLog = "error: broken"
                )
            )

            db.read { c ->
                c.createStatement().executeQuery(
                    "SELECT triage_status,discard_reason,compiler_error_log FROM commits WHERE commit_hash='c'"
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("discarded_build_broken", rs.getString("triage_status"))
                    assertEquals("build_broken", rs.getString("discard_reason"))
                    assertEquals("error: broken", rs.getString("compiler_error_log"))
                }
            }
        }
        dir.deleteRecursively()
    }
}
