package com.sksamuel.elastic4s.testkit

import java.io.PrintWriter
import java.nio.file.{Path, Paths}
import java.util.UUID

import com.sksamuel.elastic4s.{ElasticClient, ElasticDsl, Indexes}
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse
import org.elasticsearch.cluster.health.ClusterHealthStatus
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.indices.IndexAlreadyExistsException
import org.elasticsearch.transport.RemoteTransportException
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

@deprecated("Use TestNode", "5.0.0")
trait NodeBuilder extends LocalTestNode {
  this: Suite =>
}

/**
* Provides helper methods for things like refreshing an index, and blocking until an
* index has a certain count of documents. These methods are very useful when writing
* tests to allow for blocking, iterative coding
*/
trait ElasticSugar extends LocalTestNode with ElasticDsl {
  this: Suite =>

  private val logger = LoggerFactory.getLogger(getClass)

  // refresh all indexes
  def refreshAll(): RefreshResponse = refresh(Indexes.All)

  // refreshes all specified indexes
  def refresh(indexes: Indexes): RefreshResponse = {
    client.execute {
      refreshIndex(indexes)
    }.await
  }

  def blockUntilGreen(): Unit = {
    blockUntil("Expected cluster to have green status") { () =>
      client.execute {
        clusterHealth()
      }.await.getStatus == ClusterHealthStatus.GREEN
    }
  }

  def blockUntil(explain: String)(predicate: () => Boolean): Unit = {

    var backoff = 0
    var done = false

    while (backoff <= 16 && !done) {
      if (backoff > 0) Thread.sleep(200 * backoff)
      backoff = backoff + 1
      try {
        done = predicate()
      } catch {
        case e: Throwable =>
          logger.warn("problem while testing predicate", e)
      }
    }

    require(done, s"Failed waiting on: $explain")
  }

  def ensureIndexExists(index: String): Unit = {
    try {
      client.execute {
        createIndex(index)
      }.await
    } catch {
      case _: IndexAlreadyExistsException => // Ok, ignore.
      case _: RemoteTransportException => // Ok, ignore.
    }
  }

  def doesIndexExists(name: String): Boolean = {
    client.execute {
      indexExists(name)
    }.await.isExists
  }

  def deleteIndex(name: String): Unit = {
    if (doesIndexExists(name)) {
      client.execute {
        ElasticDsl.deleteIndex(name)
      }.await
    }
  }

  def truncateIndex(index: String): Unit = {
    deleteIndex(index)
    ensureIndexExists(index)
    blockUntilEmpty(index)
  }

  def blockUntilDocumentExists(id: String, index: String, `type`: String): Unit = {
    blockUntil(s"Expected to find document $id") { () =>
      client.execute {
        get(id).from(index / `type`)
      }.await.exists
    }
  }

  /**
   * Will block until the given index and optional types have at least the given number of documents.
   */
  def blockUntilCount(expected: Long, index: String, types: String*): Unit = {
    blockUntil(s"Expected count of $expected") { () =>
      val result = client.execute {
        search(index / types).matchAll().size(0)
      }.await
      expected <= result.totalHits
    }
  }

  def blockUntilExactCount(expected: Long, index: String, types: String*): Unit = {
    blockUntil(s"Expected count of $expected") { () =>
      expected == client.execute {
        search(index / types).size(0)
      }.await.totalHits
    }
  }

  def blockUntilEmpty(index: String): Unit = {
    blockUntil(s"Expected empty index $index") { () =>
      client.execute {
        search(Indexes(index)).size(0)
      }.await.totalHits == 0
    }
  }
  def blockUntilIndexExists(index: String): Unit = {
    blockUntil(s"Expected exists index $index") { () ⇒
      doesIndexExists(index)
    }
  }

  def blockUntilIndexNotExists(index: String): Unit = {
    blockUntil(s"Expected not exists index $index") { () ⇒
      !doesIndexExists(index)
    }
  }

  def blockUntilDocumentHasVersion(index: String, `type`: String, id: String, version: Long): Unit = {
    blockUntil(s"Expected document $id to have version $version") { () =>
      client.execute {
        get(id).from(index / `type`)
      }.await.version == version
    }
  }
}
