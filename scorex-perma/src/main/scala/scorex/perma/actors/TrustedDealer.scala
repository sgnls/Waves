package scorex.perma.actors

import akka.actor.{ActorLogging, Actor}
import scorex.perma.Parameters.DataSegment
import scorex.perma.actors.MinerSpec.Subset
import scorex.perma.actors.TrustedDealerSpec.{SegmentsToStore, SegmentsRequest}
import scorex.perma.merkle.MerkleTree

class TrustedDealer(val dataSet: Array[DataSegment]) extends Actor with ActorLogging {

  val tree = MerkleTree.create(dataSet)

  override def receive = {
    case SegmentsRequest(segmentIds) =>
      log.info("SegmentsRequest({})", segmentIds)

      val segments: Subset = segmentIds.map { x =>
        x -> tree.byIndex(x)
      }.toMap.collect {
        case (key, Some(value)) => key -> value
      }
      sender ! SegmentsToStore(segments)
    case m =>
      log.warning("Unknown message: {}", m)
  }

}

object TrustedDealerSpec {

  case object PublishDataset

  case class SegmentsRequest(segments: Array[Int])

  case class SegmentsToStore(segments: Subset)

}