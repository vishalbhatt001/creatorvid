package com.pixovid.backend.auth.dto;

import com.pixovid.backend.user.User;

public record UserSummary(String id, String name, String email, String image) {

  public static UserSummary of(User user) {
    return new UserSummary(user.getId(), user.getName(), user.getEmail(), user.getImage());
  }
}
