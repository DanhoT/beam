package beam.agentsim.agents.ridehail.repositioningmanager

import beam.agentsim.agents.ridehail.RideHailManager
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.reflect.ClassTag

abstract class RepositioningManager(
  private val beamServices: BeamServices,
  private val rideHailManager: RideHailManager
) {
  def repositionVehicles(tick: Int): Vector[(Id[Vehicle], Location)]
}

object RepositioningManager {

  def apply[T <: RepositioningManager](beamServices: BeamServices, rideHailManager: RideHailManager)(
    implicit ct: ClassTag[T]
  ): T = {
    val constructors = ct.runtimeClass.getDeclaredConstructors
    require(
      constructors.size == 1,
      s"Only one constructor is allowed for RepositioningManager, but $ct has ${constructors.length}"
    )
    constructors.head.newInstance(beamServices, rideHailManager).asInstanceOf[T]
  }
}

class DefaultRepositioningManager(val beamServices: BeamServices, val rideHailManager: RideHailManager)
    extends RepositioningManager(beamServices, rideHailManager) {
  override def repositionVehicles(tick: Int): Vector[(Id[Vehicle], Location)] = Vector.empty
}
