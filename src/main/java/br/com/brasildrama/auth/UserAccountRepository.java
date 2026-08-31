package br.com.brasildrama.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    Optional<UserAccount> findByGoogleSubject(String googleSubject);
}
