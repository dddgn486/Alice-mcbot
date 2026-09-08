package com.dddgn.alice.pathing.core;

/** 将纯数据 MovementSpec 在主线程转换为实时执行对象的工厂边界。 */
public interface MovementExecutionFactory {
    String factoryKey();

    boolean supports(MovementSpec spec);

    ValidationResult validate(MovementSpec spec, LiveExecutionContext context);

    MovementExecution create(MovementSpec spec, LiveExecutionContext context);

    record ValidationResult(boolean valid, String failureCode) {
        public ValidationResult {
            if (valid && failureCode != null) {
                throw new IllegalArgumentException("valid result cannot have failureCode");
            }
            if (!valid && (failureCode == null || failureCode.isBlank())) {
                throw new IllegalArgumentException("invalid result requires failureCode");
            }
        }

        public static ValidationResult accepted() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String failureCode) {
            return new ValidationResult(false, failureCode);
        }
    }
}
