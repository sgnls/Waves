package com.wavesplatform.state2.reader

import cats.implicits._
import com.wavesplatform.state2._
import scorex.account.{Address, Alias}
import scorex.transaction.assets.IssueTransaction
import scorex.transaction.lease.LeaseTransaction
import scorex.transaction.smart.Script
import scorex.transaction.{Transaction, TransactionParser}

class CompositeStateReader(inner: SnapshotStateReader, maybeDiff: => Option[Diff]) extends SnapshotStateReader {

  private def diff = maybeDiff.getOrElse(Diff.empty)

  override def portfolio(a: Address) = inner.portfolio(a).combine(diff.portfolios.getOrElse(a, Portfolio.empty))

  override def assetDescription(id: ByteStr) = {
    inner
      .assetDescription(id).orElse(diff.transactions.get(id).collectFirst {
        case (_, it: IssueTransaction, _) => AssetDescription(it.sender, it.name, it.decimals, it.reissuable, it.quantity)
      })
      .map(z => diff.issuedAssets.get(id).fold(z)(r => z.copy(reissuable = r.isReissuable, totalVolume = r.volume + z.totalVolume)))
  }

  override def leaseDetails(leaseId: ByteStr) = {
    inner.leaseDetails(leaseId).map(ld => ld.copy(isActive = diff.leaseState.getOrElse(leaseId, ld.isActive))) orElse
    diff.transactions.get(leaseId).collect { case (h, lt: LeaseTransaction, _) =>
      LeaseDetails(lt.sender, lt.recipient, h, lt.amount, diff.leaseState(lt.id()))
    }
  }

  override def transactionInfo(id: ByteStr): Option[(Int, Transaction)] =
    diff.transactions.get(id)
      .map(t => (t._1, t._2))
      .orElse(inner.transactionInfo(id))

  override def height: Int = inner.height + (if (maybeDiff.isDefined) 1 else 0)

  override def addressTransactions(address: Address,
                                   types: Set[TransactionParser.TransactionType.Value],
                                   from: Int,
                                   count: Int) = {
    val transactionsFromDiff = diff.transactions.values.view.collect {
      case (height, tx, addresses) if addresses(address) && (types(tx.transactionType) || types.isEmpty) => (height, tx)
    }.slice(from, from + count).toSeq

    val actualTxCount = transactionsFromDiff.length

    if (actualTxCount == count) transactionsFromDiff else {
      transactionsFromDiff ++ inner.addressTransactions(address, types, 0, count - actualTxCount)
    }
  }

  override def resolveAlias(a: Alias): Option[Address] = diff.aliases.get(a).orElse(inner.resolveAlias(a))

  override def allActiveLeases = diff.leaseState
    .collect { case (id, true) => diff.transactions(id)._2 }
    .collect { case lt: LeaseTransaction => lt }
    .toSet ++ inner.allActiveLeases

  override def collectPortfolios(filter: Portfolio => Boolean) = {
    inner.collectPortfolios(filter) ++
      diff.portfolios.keys.map(a => a -> portfolio(a)).filter { case (_, p) => filter(p) }.toMap
  }

  override def containsTransaction(id: ByteStr): Boolean = diff.transactions.contains(id) || inner.containsTransaction(id)

  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee =
    diff.orderFills.get(orderId).orEmpty.combine(inner.filledVolumeAndFee(orderId))

  override def balanceSnapshots(address: Address, from: Int, to: Int) = {
    if (to <= inner.height || maybeDiff.isEmpty) {
      inner.balanceSnapshots(address, from, to)
    } else {
      val bs = BalanceSnapshot(height, portfolio(address))
      if (inner.height > 0 && from < this.height) bs +: inner.balanceSnapshots(address, from, to) else Seq(bs)
    }
  }

  override def accountScript(address: Address): Option[Script] = {
    diff.scripts.get(address) match {
      case None => inner.accountScript(address)
      case Some(None) => None
      case Some(Some(scr)) => Some(scr)
    }
  }

  private def changedBalances(pred: Portfolio => Boolean, f: Address => Long): Map[Address, Long] = for {
    (address, p) <- diff.portfolios
    if pred(p)
  } yield address -> f(address)

  override def assetDistribution(height: Int, assetId: ByteStr) = {
    val innerDistribution = inner.assetDistribution(height, assetId)
    if (height < this.height) innerDistribution else {
      innerDistribution ++ changedBalances(_.assets.getOrElse(assetId, 0L) != 0, portfolio(_).assets.getOrElse(assetId, 0L))
    }
  }

  override def wavesDistribution(height: Int) = {
    val innerDistribution = inner.wavesDistribution(height)
    if (height < this.height) innerDistribution else {
      innerDistribution ++ changedBalances(_.balance != 0, portfolio(_).balance)
    }
  }
}

object CompositeStateReader {
  def composite(inner: SnapshotStateReader, diff: => Option[Diff]): SnapshotStateReader = new CompositeStateReader(inner, diff)
  def composite(inner: SnapshotStateReader, diff: Diff): SnapshotStateReader = new CompositeStateReader(inner, Some(diff))
}
