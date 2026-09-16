package orders.service;

/** A business rule was violated; the advice maps this to 409 Conflict. */
public class OrderException extends RuntimeException {

    public OrderException(String message) {
        super(message);
    }
}
