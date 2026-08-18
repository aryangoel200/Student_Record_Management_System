import { useState } from 'react';
import { useDispatch } from 'react-redux';
import { personLogin } from '../Store/Slices/personSlice';
import axiosInstance, { apiErrorMessage, storeTokens } from '../axios';

export const useSignup = () => {
    const [error, setError] = useState(null);
    const [isLoading, setIsLoading] = useState(false);
    const dispatch = useDispatch();

    const signup = async ({ name, username, email, password, password2 }) => {
        setIsLoading(true);
        setError(null);

        try {
            // One call, one record. Registration used to also POST to
            // /Home/student to create a second row for the same person; if that
            // second call failed you were left with an account and no profile.
            const { data } = await axiosInstance.post('Auth/register', {
                name,
                username,
                email,
                password,
                password2,
            });
            storeTokens(data.token);
            dispatch(personLogin(data.user));
            return true;
        } catch (err) {
            setError(apiErrorMessage(err, 'Could not create your account.'));
            return false;
        } finally {
            setIsLoading(false);
        }
    };

    return { signup, isLoading, error };
};
