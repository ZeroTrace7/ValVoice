module com.someone.valvoice {
    // JavaFX modules
    requires javafx.base;
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.media;

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
    requires dev.mccue.jlayer;

    // JNA for native Windows API (automatic module)
    requires com.sun.jna;
    requires com.sun.jna.platform;

    // Open packages to JavaFX for FXML reflection
    opens com.someone.valvoicegui to javafx.fxml, javafx.graphics;
    opens com.someone.valvoicebackend to javafx.fxml, javafx.graphics, com.google.gson;
    opens com.someone.valvoicebackend.config to com.google.gson;

    // Export packages
    exports com.someone.valvoicegui;
    exports com.someone.valvoicebackend;
    exports com.someone.valvoicebackend.config;
}

