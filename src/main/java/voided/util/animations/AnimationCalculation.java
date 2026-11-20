package voided.util.animations;

public interface AnimationCalculation {
    default double calculation(double value) {
        return 0;
    }
}
