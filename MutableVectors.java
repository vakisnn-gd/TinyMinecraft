final class MutableVec2 {
    double x;
    double z;

    MutableVec2 set(double x, double z) {
        this.x = x;
        this.z = z;
        return this;
    }
}

final class MutableVec3 {
    double x;
    double y;
    double z;

    MutableVec3 set(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }
}
