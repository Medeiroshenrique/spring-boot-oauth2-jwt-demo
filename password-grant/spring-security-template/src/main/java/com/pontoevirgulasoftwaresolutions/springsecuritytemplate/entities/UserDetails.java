package com.pontoevirgulasoftwaresolutions.springsecuritytemplate.entities;

import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

public interface UserDetails {
    public Set<GrantedAuthority> getAuthorities();
    public String getUsername();
    public boolean isAccountNonExpired();
    public boolean isAccountNonLocked();
    public boolean isCredentialsNonExpired();
    public boolean isEnabled();
}
