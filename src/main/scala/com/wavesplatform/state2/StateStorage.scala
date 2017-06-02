package com.wavesplatform.state2

import com.google.common.primitives.Ints
import com.wavesplatform.state2.StateStorage.SnapshotKey
import org.h2.mvstore.{MVMap, MVStore}
import scorex.account.Account

class StateStorage private(db: MVStore) {

  import StateStorage._

  private val variables: MVMap[String, Int] = db.openMap("variables")

  private def setPersistedVersion(version: Int) = variables.put(stateVersion, version)

  private def persistedVersion: Option[Int] = Option(variables.get(stateVersion))

  private def setDirty(isDirty: Boolean): Unit = variables.put(isDirtyFlag, if (isDirty) 1 else 0)

  private def dirty(): Boolean = variables.get(isDirtyFlag) == 1

  def getHeight: Int = variables.get(heightKey)

  def setHeight(i: Int): Unit = variables.put(heightKey, i)

  val transactions: MVMap[Array[Byte], (Int, Array[Byte])] = db.openMap("txs")

  val portfolios: MVMap[Array[Byte], (Long, (Long, Long), Map[Array[Byte], Long])] = db.openMap("portfolios")

  val assets: MVMap[Array[Byte], (Boolean, Long)] = db.openMap("assets")

  val accountTransactionIds: MVMap[Array[Byte], List[Array[Byte]]] = db.openMap("accountTransactionIds")

  val balanceSnapshots: MVMap[SnapshotKey, (Int, Long, Long)] = db.openMap("balanceSnapshots")

  val paymentTransactionHashes: MVMap[Array[Byte], Array[Byte]] = db.openMap("paymentTransactionHashes")

  val aliasToAddress: MVMap[String, Array[Byte]] = db.openMap("aliasToAddress")

  val orderFills: MVMap[Array[Byte], (Long, Long)] = db.openMap("orderFills")

  val leaseState: MVMap[Array[Byte], Boolean] = db.openMap("leaseState")

  val lastUpdateHeight: MVMap[Array[Byte], Int] = db.openMap("lastUpdateHeight")

  val uniqueAssets: MVMap[Array[Byte], Array[Byte]] = db.openMap("uniqueAssets")

  def commit(): Unit = db.commit()

}

object StateStorage {

  private val VERSION = 1

  private val heightKey = "height"
  private val isDirtyFlag = "isDirty"
  private val stateVersion = "stateVersion"

  def apply(db: MVStore): Either[String, StateStorage] = {
    val s = new StateStorage(db)

    if (s.dirty())
      Left("Persisted state is corrupt")
    else {
      s.persistedVersion match {
        case None =>
          s.setPersistedVersion(VERSION)
          s.commit()
          Right(s)
        case Some(`VERSION`) => Right(s)
        case Some(pv) => Left(s"Persisted state has version $pv, current scheme version is $VERSION")

      }
    }
  }

  type SnapshotKey = Array[Byte]

  def snapshotKey(acc: Account, height: Int): SnapshotKey = acc.bytes ++ Ints.toByteArray(height)

  def dirty[R](p: StateStorage)(f: => R): R = {
    p.setDirty(true)
    val r = f
    p.setDirty(false)
    r
  }
}