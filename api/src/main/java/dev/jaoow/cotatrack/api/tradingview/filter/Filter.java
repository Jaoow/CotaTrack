package dev.jaoow.cotatrack.api.tradingview.filter;


import dev.jaoow.cotatrack.api.tradingview.field.FieldKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.*;

@Getter
@Setter
public class Filter {

    private final FieldKey field;
    private Operator operation;
    private final List<String> values;

    public Filter(FieldKey field, Operator operation, List<String> values) {
        this.field = field;
        this.operation = operation;
        this.values = new ArrayList<>(values);

        if (values.size() == 1 && operation == Operator.IN_RANGE) {
            this.operation = Operator.EQUAL;
        }
    }

    // Copy constructor
    public Filter(Filter filter) {
        this.field = filter.field;
        this.operation = filter.operation;
        this.values = new ArrayList<>(filter.values);
    }

    public void merge(Filter filter) {
       HashSet<Object> filterValues = new HashSet<>(this.values);

       if (!filterValues.containsAll(filter.getValues())) {
           this.values.addAll(filter.getValues());
           this.operation = Filter.Operator.IN_RANGE;
       }
    }

    public Map<String, Object> toDict() {
        List<String> right = new ArrayList<>(values);
        Object rightValue = right.size() == 1 ? right.get(0) : right;

        Map<String, Object> dict = new HashMap<>();
        dict.put("left", field.getKey());
        dict.put("operation", this.operation.getValue());
        dict.put("right", rightValue);
        return dict;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Operator {
        BELOW("less"),
        BELOW_OR_EQUAL("eless"),
        ABOVE("greater"),
        ABOVE_OR_EQUAL("egreater"),
        CROSSES("crosses"),
        CROSSES_UP("crosses_above"),
        CROSSES_DOWN("crosses_below"),
        IN_RANGE("in_range"),
        NOT_IN_RANGE("not_in_range"),
        EQUAL("equal"),
        NOT_EQUAL("nequal"),
        MATCH("match");

        private final String value;
    }
}