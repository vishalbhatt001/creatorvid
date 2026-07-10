package com.pixovid.backend.credits;

/** The billable generation actions; each has a fixed credit price (see AppProperties.Credits). */
public enum GenerationAction {
  VIDEO,
  IMAGE,
  TEMPLATE_RENDER;

  /** The reference type stored on ledger rows for this action (matches the DB rows' referenceType). */
  public String referenceType() {
    return name().toLowerCase();
  }
}
