module com.someone.valvoice {
    // JavaFX modules
    requires javafx.base;
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;

    // JFoenix for Material Design components
    requires com.jfoenix;

    // Logging
    requires org.slf4j;
    requires ch.qos.logback.classic;
    requires ch.qos.logback.core;

    // JSON processing
    requires com.google.gson;

    // Java modules
    requires java.desktop;
    requires java.net.http;

    // Open packages to JavaFX for FXML reflection
    opens com.someone.valvoicegui to javafx.fxml, javafx.graphics;
    opens com.someone.valvoicebackend to javafx.fxml, javafx.graphics, com.google.gson;

    // Export packages
    exports com.someone.valvoicegui;
    exports com.someone.valvoicebackend;
}

