abstract class Entity {
    double x;
    double y;
    double z;
    double previousX;
    double previousY;
    double previousZ;
    double velocityX;
    double velocityZ;
    double verticalVelocity;
    double fallDistance;
    boolean isGrounded;
    double stepTimer;
    double health;
    int airUnits;
    double airUnitTimer;
    double drowningTimer;
    double lavaDamageTimer;
    double fireTimer;
    double fireDamageTimer;
    boolean wasInWater;

    Entity(double x, double y, double z, double health) {
        setPosition(x, y, z);
        this.health = health;
        this.airUnits = GameConfig.MAX_AIR_UNITS;
    }

    final void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.previousX = x;
        this.previousY = y;
        this.previousZ = z;
    }

    final void capturePreviousPosition() {
        this.previousX = x;
        this.previousY = y;
        this.previousZ = z;
    }

    abstract double radius();

    abstract double height();
}
