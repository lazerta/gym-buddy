package com.gymbuddy.domain

enum class MovementFamily {
    PRESS,
    PULL,
    SQUAT,
    HINGE,
    RAISE,
    CURL,
    EXTENSION,
    CORE,
    LOCOMOTION,
    TRANSITION,
    HOLD,
    UNKNOWN,
}

enum class MovementPrimitiveType {
    PRESS,
    PULL,
    SQUAT,
    HINGE,
    RAISE,
    CURL,
    EXTENSION,
    CORE,
    LOCOMOTION,
    TRANSITION,
    HOLD,
    UNKNOWN,
}

enum class ViewClass {
    FRONT,
    FRONT_OBLIQUE,
    SIDE,
    SIDE_OBLIQUE,
    REAR,
    REAR_OBLIQUE,
    UNKNOWN,
}

enum class LensFacing {
    FRONT,
    BACK,
}

enum class LateralityMode {
    BILATERAL,
    UNILATERAL_LEFT,
    UNILATERAL_RIGHT,
    ALTERNATING,
    NOT_APPLICABLE,
}

enum class SignalKind {
    JOINT_ANGLE,
    NORMALIZED_POINT_DISTANCE,
    BODY_LOCAL_DISPLACEMENT,
    VELOCITY,
    DIRECTION,
    REVERSAL,
    PHASE_DWELL,
    ROM_PROXY,
    BILATERAL_TIMING_DIFFERENCE,
    TRAJECTORY_DEVIATION,
    HOLD_DURATION,
    CONFIDENCE,
}

enum class MetricUnit {
    DEGREES,
    NORMALIZED_DISTANCE,
    NORMALIZED_DISPLACEMENT,
    NORMALIZED_RANGE,
    SECONDS,
    MILLISECONDS,
    RATIO,
    COUNT,
    UNITLESS,
}

enum class RuleComparator {
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    OUTSIDE_RANGE,
}

enum class CueSeverity {
    INFO,
    MINOR,
    MAJOR,
}

enum class EquipmentKind {
    DUMBBELL,
    BARBELL,
    SMITH_MACHINE,
    BENCH,
    SELECTORIZED_MACHINE,
    CABLE,
    BODYWEIGHT,
    OTHER,
    UNKNOWN,
}

